package kr.co.legalai;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import kr.co.legalai.auth.dto.request.LoginRequest;
import kr.co.legalai.auth.dto.request.RegisterRequest;
import kr.co.legalai.auth.dto.response.AuthTokenResponse;
import kr.co.legalai.auth.security.IdentityCrypto;
import kr.co.legalai.auth.service.AuthService;
import kr.co.legalai.casework.dto.request.CreateCaseRequest;
import kr.co.legalai.casework.dto.request.UpdateCaseRequest;
import kr.co.legalai.casework.service.CaseService;
import kr.co.legalai.common.exception.CaseNotFoundException;
import kr.co.legalai.common.exception.BusinessException;
import kr.co.legalai.common.exception.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CaseFlowIntegrationTest {
    private static final UUID USER_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final KeyPair JWT_KEY_PAIR = generateRsaKeyPair();
    private static final String TEST_SECRET = Base64.getEncoder().encodeToString(new byte[32]);

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

    @Autowired
    private AuthService authService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IdentityCrypto identityCrypto;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "legal_ai_app");
        registry.add("spring.datasource.password", () -> "app_test");
        // 동일 연결을 재사용해 사용자 범위가 다음 요청으로 새지 않는지도 검증한다.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
        registry.add("spring.auth-datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.auth-datasource.username", () -> "legal_ai_auth");
        registry.add("spring.auth-datasource.password", () -> "auth_test");
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://issuer.test");
        registry.add("security.jwt.private-key-base64", () -> Base64.getEncoder().encodeToString(
                JWT_KEY_PAIR.getPrivate().getEncoded()
        ));
        registry.add("security.jwt.public-key-base64", () -> Base64.getEncoder().encodeToString(
                JWT_KEY_PAIR.getPublic().getEncoded()
        ));
        registry.add("security.identity.encryption-key-base64", () -> TEST_SECRET);
        registry.add("security.identity.lookup-key-base64", () -> TEST_SECRET);
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
            assertEquals(42, result.getInt("table_count"));
            assertEquals(result.getInt("table_count"), result.getInt("described_table_count"));
            assertEquals(47, result.getInt("described_column_count"));
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

    @Test
    void registerLoginAndMeUseProtectedIdentityData() throws Exception {
        String email = "User-" + UUID.randomUUID() + "@Example.com";
        var registered = authService.register(new RegisterRequest(
                email,
                "secure-password-123",
                "임차인",
                true,
                true
        ));

        SignedJWT accessToken = SignedJWT.parse(registered.accessToken());
        assertTrue(accessToken.verify(new RSASSAVerifier((RSAPublicKey) JWT_KEY_PAIR.getPublic())));
        assertEquals("http://issuer.test", accessToken.getJWTClaimsSet().getIssuer());
        assertEquals("better-call-ai", accessToken.getJWTClaimsSet().getAudience().getFirst());

        UUID userId = UUID.fromString(accessToken.getJWTClaimsSet().getSubject());
        authenticate(userId);
        var me = authService.getMe();
        assertEquals(email.toLowerCase(), me.email());
        assertEquals("임차인", me.displayName());

        var loggedIn = authService.login(new LoginRequest(email.toUpperCase(), "secure-password-123"));
        assertTrue(loggedIn.accessTokenExpiresAt().isBefore(loggedIn.refreshTokenExpiresAt()));
        assertRefreshTokenIsHashed(registered.refreshToken());
    }

    @Test
    void duplicateEmailAndWrongPasswordAreRejected() {
        String email = "duplicate-" + UUID.randomUUID() + "@example.com";
        var request = new RegisterRequest(email, "secure-password-123", "사용자", true, true);
        authService.register(request);

        BusinessException duplicate = assertThrows(BusinessException.class, () -> authService.register(request));
        assertEquals(ErrorCode.EMAIL_ALREADY_REGISTERED, duplicate.getErrorCode());

        BusinessException wrongPassword = assertThrows(BusinessException.class, () ->
                authService.login(new LoginRequest(email, "wrong-password"))
        );
        assertEquals(ErrorCode.INVALID_CREDENTIALS, wrongPassword.getErrorCode());
    }

    @Test
    void refreshTokenRotatesAndReuseRevokesTheReplacement() {
        String email = "rotation-" + UUID.randomUUID() + "@example.com";
        var registered = authService.register(new RegisterRequest(
                email,
                "secure-password-123",
                "사용자",
                true,
                true
        ));
        var rotated = authService.refresh(registered.refreshToken());

        BusinessException reused = assertThrows(BusinessException.class, () ->
                authService.refresh(registered.refreshToken())
        );
        assertEquals(ErrorCode.REFRESH_TOKEN_REUSED, reused.getErrorCode());

        BusinessException replacementRevoked = assertThrows(BusinessException.class, () ->
                authService.refresh(rotated.refreshToken())
        );
        assertEquals(ErrorCode.INVALID_REFRESH_TOKEN, replacementRevoked.getErrorCode());
    }

    @Test
    void registrationIsPublicButMeRequiresAuthentication() throws Exception {
        String email = "http-" + UUID.randomUUID() + "@example.com";
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email": "%s",
                                  "password": "secure-password-123",
                                  "displayName": "사용자",
                                  "termsAccepted": true,
                                  "privacyAccepted": true
                                }
                                """.formatted(email)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"));

        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));

        mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("RSA"))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());

        var tokenPair = authService.register(new RegisterRequest(
                "bearer-" + UUID.randomUUID() + "@example.com",
                "secure-password-123",
                "인증 사용자",
                true,
                true
        ));
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + tokenPair.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("인증 사용자"));
    }

    private void authenticate(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(userId.toString(), null)
        );
    }

    @Test
    void httpCaseOwnershipRejectsForeignReadsAndWritesWithoutChangingData() throws Exception {
        var owner = registerTestUser();
        var other = registerTestUser();
        UUID ownerId = tokenUserId(owner);
        UUID caseId = createHttpCase(owner, tokenUserId(other));
        assertEquals(ownerId, storedCaseOwner(caseId));
        String originalSnapshot = caseSnapshot(caseId);
        int originalOutboxCount = outboxCount(caseId);

        // 존재하는 타인 사건과 없는 사건 모두 같은 공개 오류로 응답한다.
        for (UUID target : List.of(caseId, UUID.randomUUID())) {
            mockMvc.perform(get("/api/v1/cases/{caseId}", target)
                            .header("Authorization", "Bearer " + other.accessToken())
                            .header("X-User-Id", ownerId.toString())
                            .param("userId", ownerId.toString()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("CASE_001"))
                    .andExpect(jsonPath("$.message").value(ErrorCode.CASE_NOT_FOUND.message()))
                    .andExpect(jsonPath("$.data").doesNotExist());

            // 버전을 맞히거나 틀려도 타인은 409 대신 404를 받는다.
            for (int version : List.of(1, 99)) {
                mockMvc.perform(patch("/api/v1/cases/{caseId}", target)
                                .header("Authorization", "Bearer " + other.accessToken())
                                .header("X-User-Id", ownerId.toString())
                                .param("userId", ownerId.toString())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"originalStatement":"타인의 변경 시도","expectedVersion":%d,
                                         "userId":"%s","ownerUserId":"%s"}
                                        """.formatted(version, ownerId, ownerId)))
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.code").value("CASE_001"))
                        .andExpect(jsonPath("$.message").value(ErrorCode.CASE_NOT_FOUND.message()));
            }
        }
        assertEquals(originalSnapshot, caseSnapshot(caseId));
        assertEquals(originalOutboxCount, outboxCount(caseId));

        // 차단된 트랜잭션 뒤 같은 연결을 재사용해도 본인 요청은 정상 처리된다.
        mockMvc.perform(get("/api/v1/cases/{caseId}", caseId)
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.originalStatement").value("계약서 누수 분쟁"));
        mockMvc.perform(patch("/api/v1/cases/{caseId}", caseId)
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originalStatement\":\"소유자의 변경\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(2));
        assertEquals(originalOutboxCount + 1, outboxCount(caseId));
        String updatedSnapshot = caseSnapshot(caseId);
        mockMvc.perform(patch("/api/v1/cases/{caseId}", caseId)
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originalStatement\":\"오래된 버전 변경\",\"expectedVersion\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CASE_002"));
        assertEquals(updatedSnapshot, caseSnapshot(caseId));
        assertEquals(originalOutboxCount + 1, outboxCount(caseId));
    }

    @Test
    void httpCaseCreationUsesJwtSubjectAndUnauthenticatedRequestsAreRejected() throws Exception {
        var owner = registerTestUser();
        var other = registerTestUser();
        UUID caseId = createHttpCase(other, tokenUserId(owner));
        assertEquals(tokenUserId(other), storedCaseOwner(caseId));
        String snapshot = caseSnapshot(caseId);
        mockMvc.perform(get("/api/v1/cases/{caseId}", caseId)
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/cases/{caseId}", caseId)
                        .header("Authorization", "Bearer " + other.accessToken()))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/cases/{caseId}", caseId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_001"));
        mockMvc.perform(patch("/api/v1/cases/{caseId}", caseId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originalStatement\":\"익명 변경\",\"expectedVersion\":1}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"익명 생성\"}"))
                .andExpect(status().isUnauthorized());
        assertEquals(snapshot, caseSnapshot(caseId));
        assertEquals(1, outboxCount(caseId));
        // 요청이 끝난 연결에는 직전 사용자의 읽기 권한이 남지 않는다.
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM casework.cases", Integer.class));
    }

    private UUID createHttpCase(AuthTokenResponse token, UUID claimedOwner) throws Exception {
        var response = mockMvc.perform(post("/api/v1/cases")
                        .header("Authorization", "Bearer " + token.accessToken())
                        .header("X-User-Id", claimedOwner.toString())
                        .param("userId", claimedOwner.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"소유권 검증 사건","originalStatement":"계약서 누수 분쟁",
                                 "userId":"%s","ownerUserId":"%s"}
                                """.formatted(claimedOwner, claimedOwner)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.version").value(1))
                .andReturn().getResponse();
        return UUID.fromString(objectMapper.readTree(response.getContentAsString()).path("data").path("id").asString());
    }

    private UUID storedCaseOwner(UUID caseId) throws SQLException {
        try (var connection = adminConnection();
             var statement = connection.prepareStatement("SELECT owner_user_id FROM casework.cases WHERE id = ?")) {
            statement.setObject(1, caseId);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getObject(1, UUID.class);
            }
        }
    }

    private String caseSnapshot(UUID caseId) throws SQLException {
        try (var connection = adminConnection();
             var statement = connection.prepareStatement("SELECT to_jsonb(c)::text FROM casework.cases c WHERE id = ?")) {
            statement.setObject(1, caseId);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    @Test
    void logoutIsIdempotentAndCannotRevokeAnotherUsersToken() throws Exception {
        var owner = registerTestUser();
        var other = registerTestUser();
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + other.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + owner.refreshToken() + "\"}"))
                .andExpect(status().isNoContent());
        var rotated = authService.refresh(owner.refreshToken());
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/auth/logout")
                            .header("Authorization", "Bearer " + owner.accessToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"" + rotated.refreshToken() + "\"}"))
                    .andExpect(status().isNoContent());
        }
        assertAuthError(ErrorCode.INVALID_REFRESH_TOKEN, () -> authService.refresh(rotated.refreshToken()));
        // Stateless Access Token은 로그아웃 후에도 만료 전까지 유효하다.
        mockMvc.perform(get("/api/v1/auth/me")
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk());
        assertTrue(authService.refresh(other.refreshToken()).accessToken() != null);
    }

    @Test
    void expiredRefreshTokenIsRejectedAndRevocationIsCommitted() throws Exception {
        var pair = registerTestUser();
        UUID userId = tokenUserId(pair);
        try (var connection = adminConnection();
             var statement = connection.prepareStatement("""
                     UPDATE identity.refresh_tokens
                     SET created_at = now() - interval '2 days', expires_at = now() - interval '1 day'
                     WHERE user_id = ?
                     """)) {
            statement.setObject(1, userId);
            assertEquals(1, statement.executeUpdate());
        }
        assertAuthError(ErrorCode.INVALID_REFRESH_TOKEN, () -> authService.refresh(pair.refreshToken()));
        assertEquals(0, activeRefreshCount(userId));
    }

    @Test
    void concurrentRefreshAllowsOneRotationAndRevokesWinnerOnReuse() throws Exception {
        var pair = registerTestUser();
        var outcomes = concurrently(List.of(
                () -> refreshOutcome(pair.refreshToken()),
                () -> refreshOutcome(pair.refreshToken())
        ));
        assertEquals(1, outcomes.stream().filter(AuthTokenResponse.class::isInstance).count());
        assertEquals(1, outcomes.stream().filter(ErrorCode.REFRESH_TOKEN_REUSED::equals).count());
        assertEquals(0, activeRefreshCount(tokenUserId(pair)));
    }

    @Test
    void parallelReplaysAcrossSessionsDoNotDeadlockOrLeaveActiveTokens() throws Exception {
        String email = "sessions-" + UUID.randomUUID() + "@example.com";
        var first = authService.register(new RegisterRequest(email, "secure-password-123", "사용자", true, true));
        var second = authService.login(new LoginRequest(email, "secure-password-123"));
        authService.refresh(first.refreshToken());
        authService.refresh(second.refreshToken());
        var outcomes = concurrently(List.of(
                () -> refreshOutcome(first.refreshToken()),
                () -> refreshOutcome(second.refreshToken())
        ));
        assertTrue(outcomes.stream().allMatch(ErrorCode.REFRESH_TOKEN_REUSED::equals));
        assertEquals(0, activeRefreshCount(tokenUserId(first)));
    }

    @Test
    void suspendedAccountCannotRefreshAndRevokesAllSessions() throws Exception {
        var pair = registerTestUser();
        UUID userId = tokenUserId(pair);
        try (var connection = adminConnection();
             var statement = connection.prepareStatement(
                     "UPDATE identity.users SET status = 'suspended' WHERE id = ?")) {
            statement.setObject(1, userId);
            statement.executeUpdate();
        }
        assertAuthError(ErrorCode.ACCOUNT_UNAVAILABLE, () -> authService.refresh(pair.refreshToken()));
        assertEquals(0, activeRefreshCount(userId));
    }

    @Test
    void applicationRoleCannotReadOrResetLoginLimits() throws SQLException {
        try (var connection = appConnection(); var statement = connection.createStatement()) {
            assertEquals("42501", assertThrows(SQLException.class, () ->
                    statement.executeQuery("SELECT * FROM identity.login_attempts")).getSQLState());
            assertEquals("42501", assertThrows(SQLException.class, () ->
                    statement.executeUpdate("DELETE FROM identity.login_attempts")).getSQLState());
        }
    }

    @Test
    void loginLocksRegisteredAndUnknownEmailsOnFifthFailure() throws Exception {
        String email = "limit-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(email, "secure-password-123", "사용자", true, true));
        for (String address : List.of(email, "unknown-" + UUID.randomUUID() + "@example.com")) {
            for (int attempt = 1; attempt <= 5; attempt++) {
                mockMvc.perform(post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"email\":\"" + address.toUpperCase(java.util.Locale.ROOT)
                                        + "\",\"password\":\"wrong-password\"}"))
                        .andExpect(status().is(attempt < 5 ? 401 : 429))
                        .andExpect(jsonPath("$.code").value(attempt < 5 ? "AUTH_004" : "AUTH_008"));
            }
        }
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"secure-password-123\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void loginSuccessAndExpiredLockResetFailures() throws Exception {
        String email = "reset-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(email, "secure-password-123", "사용자", true, true));
        for (int attempt = 0; attempt < 4; attempt++) {
            assertAuthError(ErrorCode.INVALID_CREDENTIALS, () ->
                    authService.login(new LoginRequest(email, "wrong-password")));
        }
        authService.login(new LoginRequest(email, "secure-password-123"));
        for (int attempt = 0; attempt < 4; attempt++) {
            assertAuthError(ErrorCode.INVALID_CREDENTIALS, () ->
                    authService.login(new LoginRequest(email, "wrong-password")));
        }
        assertThrows(BusinessException.class, () -> authService.login(new LoginRequest(email, "wrong-password")));
        String emailHash = identityCrypto.lookupHash(email);
        try (var connection = adminConnection();
             var statement = connection.prepareStatement("""
                     UPDATE identity.login_attempts SET locked_until = now() - interval '1 second'
                     WHERE email_lookup_hash = ?
                     """)) {
            statement.setString(1, emailHash);
            assertEquals(1, statement.executeUpdate());
        }
        assertAuthError(ErrorCode.INVALID_CREDENTIALS, () ->
                authService.login(new LoginRequest(email, "wrong-password")));
        assertTrue(authService.login(new LoginRequest(email, "secure-password-123")).accessToken() != null);
    }

    @Test
    void concurrentLoginFailuresCannotBypassLimit() throws Exception {
        String email = "parallel-" + UUID.randomUUID() + "@example.com";
        authService.register(new RegisterRequest(email, "secure-password-123", "사용자", true, true));
        Callable<ErrorCode> attempt = () -> assertThrows(BusinessException.class, () ->
                authService.login(new LoginRequest(email, "wrong-password"))).getErrorCode();
        var outcomes = concurrently(List.of(attempt, attempt, attempt, attempt, attempt, attempt));
        assertEquals(4, outcomes.stream().filter(ErrorCode.INVALID_CREDENTIALS::equals).count());
        assertEquals(2, outcomes.stream().filter(code -> code.code().equals("AUTH_008")).count());
    }

    private AuthTokenResponse registerTestUser() {
        return authService.register(new RegisterRequest(
                "auth-" + UUID.randomUUID() + "@example.com", "secure-password-123", "사용자", true, true));
    }

    private UUID tokenUserId(AuthTokenResponse pair) throws Exception {
        return UUID.fromString(SignedJWT.parse(pair.accessToken()).getJWTClaimsSet().getSubject());
    }

    private Object refreshOutcome(String token) {
        try {
            return authService.refresh(token);
        } catch (BusinessException exception) {
            return exception.getErrorCode();
        }
    }

    private <T> List<T> concurrently(List<Callable<T>> tasks) throws Exception {
        var ready = new CountDownLatch(tasks.size());
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(tasks.size());
        try {
            var futures = tasks.stream().map(task -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("동시 요청 시작 시간 초과");
                }
                return task.call();
            })).toList();
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            var results = new java.util.ArrayList<T>();
            for (var future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private void assertAuthError(ErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(BusinessException.class, action).getErrorCode());
    }

    private int activeRefreshCount(UUID userId) throws SQLException {
        try (var connection = adminConnection();
             var statement = connection.prepareStatement("""
                     SELECT count(*) FROM identity.refresh_tokens WHERE user_id = ? AND revoked_at IS NULL
                     """)) {
            statement.setObject(1, userId);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
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

    private void assertRefreshTokenIsHashed(String rawToken) throws SQLException {
        try (Connection connection = adminConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT token_hash FROM identity.refresh_tokens
                     ORDER BY created_at DESC LIMIT 1
                     """)) {
            result.next();
            String stored = result.getString(1);
            assertEquals(64, stored.length());
            assertTrue(!stored.equals(rawToken));
        }
    }

    private static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
