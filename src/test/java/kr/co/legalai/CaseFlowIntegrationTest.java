package kr.co.legalai;

import kr.co.legalai.casework.api.CreateCaseRequest;
import kr.co.legalai.casework.api.UpdateStatementRequest;
import kr.co.legalai.casework.application.CaseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        var created = service.create(new CreateCaseRequest(
                "중고거래 환불 분쟁",
                "구매자",
                null,
                "물건이 설명과 달랐습니다."
        ));

        var updated = service.updateStatement(
                created.id(),
                new UpdateStatementRequest(
                        "판매자가 하자를 고지하지 않았습니다.",
                        1
                )
        );

        assertEquals(2, updated.version());
        assertEquals("COLLECTING", updated.status());
        assertEquals(2, outboxCount(created.id()));

        authenticate(USER_B);
        assertThrows(ResponseStatusException.class, () -> service.get(created.id()));
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
}
