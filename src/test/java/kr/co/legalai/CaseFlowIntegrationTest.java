package kr.co.legalai;

import kr.co.legalai.casework.dto.request.CreateCaseRequest;
import kr.co.legalai.casework.dto.request.UpdateCaseRequest;
import kr.co.legalai.casework.service.CaseService;
import kr.co.legalai.common.exception.CaseNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class CaseFlowIntegrationTest {
    private static final UUID USER_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16")
                    .asCompatibleSubstituteFor("postgres")
    );

    static {
        POSTGRES.start();
        try {
            var result = POSTGRES.execInContainer(
                    "psql",
                    "-U", POSTGRES.getUsername(),
                    "-d", POSTGRES.getDatabaseName(),
                    "-v", "ON_ERROR_STOP=1",
                    "-c",
                    """
                    CREATE ROLE legal_ai_app LOGIN PASSWORD 'app_test' NOBYPASSRLS;
                    CREATE ROLE legal_ai_auth LOGIN PASSWORD 'auth_test' BYPASSRLS;
                    """
            );
            if (result.getExitCode() != 0) {
                throw new IllegalStateException(result.getStderr());
            }
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Autowired
    private CaseService service;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "legal_ai_app");
        registry.add("spring.datasource.password", () -> "app_test");
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://issuer.test");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://issuer.test/jwks");
    }

    @BeforeEach
    void createUsers() throws SQLException {
        try (Connection connection = adminConnection();
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                    INSERT INTO identity.users(id, status)
                    VALUES ('%s', 'active'), ('%s', 'active')
                    ON CONFLICT (id) DO NOTHING
                    """.formatted(USER_A, USER_B));
        }
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void caseWriteIsVersionedAndIsolatedByRls() throws SQLException {
        authenticate(USER_A);
        var created = service.createCase(new CreateCaseRequest(
                "중고거래 환불 분쟁",
                "구매자",
                null,
                "물건이 설명과 달랐습니다."
        ));

        var updated = service.updateCase(
                created.id(),
                new UpdateCaseRequest(
                        "판매자가 하자를 고지하지 않았습니다.",
                        1
                )
        );

        assertEquals(2, updated.version());
        assertEquals("COLLECTING", updated.status());
        assertEquals(2, outboxCount(created.id()));

        authenticate(USER_B);
        assertThrows(CaseNotFoundException.class, () -> service.getCase(created.id()));
    }

    @Test
    void schemaDescriptionsCoverAllBusinessTablesAndCriticalColumns() throws SQLException {
        try (Connection connection = adminConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT
                         count(DISTINCT c.oid) AS table_count,
                         count(DISTINCT c.oid) FILTER (
                             WHERE obj_description(c.oid, 'pg_class') IS NOT NULL
                         ) AS described_table_count,
                         count(*) FILTER (
                             WHERE col_description(c.oid, a.attnum) IS NOT NULL
                         ) AS described_column_count
                     FROM pg_class c
                     JOIN pg_namespace n ON n.oid = c.relnamespace
                     JOIN pg_attribute a ON a.attrelid = c.oid
                         AND a.attnum > 0
                         AND NOT a.attisdropped
                     WHERE c.relkind = 'r'
                       AND n.nspname IN (
                           'identity', 'casework', 'knowledge', 'aiops',
                           'workflow', 'audit', 'ops'
                       )
                     """)) {
            result.next();
            assertEquals(41, result.getInt("table_count"));
            assertEquals(result.getInt("table_count"), result.getInt("described_table_count"));
            assertEquals(43, result.getInt("described_column_count"));
        }
    }

    @Test
    void securityAndAiGuardrailsAreInstalled() throws SQLException {
        try (Connection connection = adminConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT system_prompt, output_schema, model_policy
                     FROM aiops.prompt_versions
                     WHERE prompt_key = 'case_analysis' AND is_active
                     """)) {
            result.next();
            assertTrue(result.getString("system_prompt").contains("신뢰할 수 없는 데이터"));
            assertTrue(result.getString("system_prompt").contains("사건번호"));
            assertTrue(result.getString("output_schema").contains("insufficient_evidence"));
            assertTrue(result.getString("model_policy").contains("max_output_tokens"));
        }

        try (Connection connection = adminConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT count(*)
                     FROM ops.runtime_settings
                     WHERE (setting_key = 'rag.max_results' AND value_json = '8'::jsonb)
                        OR (setting_key = 'model.max_output_tokens' AND value_json = '1200'::jsonb)
                        OR (setting_key = 'outbox.max_attempts' AND value_json = '5'::jsonb)
                     """)) {
            result.next();
            assertEquals(3, result.getInt(1));
        }
    }

    @Test
    void databaseRejectsPlainPasswordsRawRefreshTokensAndPathNames() throws SQLException {
        try (Connection connection = adminConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO identity.auth_identities(
                         user_id, provider, provider_subject, password_hash
                     ) VALUES (?, 'local', 'unsafe-password-test', 'plain-password')
                     """)) {
            statement.setObject(1, USER_A);
            assertThrows(SQLException.class, statement::executeUpdate);
        }

        try (Connection connection = adminConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO identity.refresh_tokens(user_id, token_hash, expires_at)
                     VALUES (?, 'raw-refresh-token', now() + interval '1 day')
                     """)) {
            statement.setObject(1, USER_A);
            assertThrows(SQLException.class, statement::executeUpdate);
        }

        authenticate(USER_A);
        var created = service.createCase(new CreateCaseRequest(
                "파일명 검증 사건",
                "임차인",
                null,
                "누수 증거를 정리합니다."
        ));
        try (Connection connection = adminConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO casework.files(
                         case_id, uploaded_by, file_type, original_name,
                         mime_type, size_bytes, sha256
                     ) VALUES (?, ?, 'document', '../contract.pdf',
                               'application/pdf', 10, ?)
                     """)) {
            statement.setObject(1, created.id());
            statement.setObject(2, USER_A);
            statement.setString(3, "a".repeat(64));
            assertThrows(SQLException.class, statement::executeUpdate);
        }
    }

    @Test
    void anotherUserCannotReadFileMetadata() throws SQLException {
        authenticate(USER_A);
        var created = service.createCase(new CreateCaseRequest(
                "파일 소유권 사건",
                "임차인",
                null,
                "계약서 OCR을 준비합니다."
        ));
        UUID fileId = UUID.randomUUID();

        try (Connection connection = adminConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO casework.files(
                         id, case_id, uploaded_by, file_type, original_name,
                         mime_type, size_bytes, sha256
                     ) VALUES (?, ?, ?, 'document', 'contract.pdf',
                               'application/pdf', 10, ?)
                     """)) {
            statement.setObject(1, fileId);
            statement.setObject(2, created.id());
            statement.setObject(3, USER_A);
            statement.setString(4, "b".repeat(64));
            statement.executeUpdate();
        }

        try (Connection connection = appConnection()) {
            connection.setAutoCommit(false);
            try (var scope = connection.prepareStatement("SELECT set_config('app.user_id', ?, true)")) {
                scope.setString(1, USER_B.toString());
                scope.execute();
            }
            try (var statement = connection.prepareStatement(
                    "SELECT count(*) FROM casework.files WHERE id = ?"
            )) {
                statement.setObject(1, fileId);
                try (var result = statement.executeQuery()) {
                    result.next();
                    assertEquals(0, result.getInt(1));
                }
            } finally {
                connection.rollback();
            }
        }
    }

    private void authenticate(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(userId.toString(), null)
        );
    }

    private int outboxCount(UUID caseId) throws SQLException {
        try (Connection connection = adminConnection();
             var statement = connection.prepareStatement(
                     "SELECT count(*) FROM ops.outbox_events WHERE aggregate_id = ?"
             )) {
            statement.setObject(1, caseId);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword()
        );
    }

    private Connection appConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGRES.getJdbcUrl(),
                "legal_ai_app",
                "app_test"
        );
    }
}
