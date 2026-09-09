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
import org.springframework.mock.web.MockMultipartFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import kr.co.legalai.file.service.FileCleanupService;
import kr.co.legalai.file.repository.FileCleanupRepository;
import kr.co.legalai.file.repository.ClamAvRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CaseFlowIntegrationTest {
    @MockitoBean
    private kr.co.legalai.chat.repository.OpenAiChatRepository openAiChat;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private kr.co.legalai.chat.repository.ChatRepository chatRepository;
    @MockitoBean
    private ClamAvRepository malwareScanner;
    private static final UUID USER_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_B = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final KeyPair JWT_KEY_PAIR = generateRsaKeyPair();
    private static final String TEST_SECRET = Base64.getEncoder().encodeToString(new byte[32]);
    @org.junit.jupiter.api.io.TempDir
    static Path uploadRoot;

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
    private FileCleanupService fileCleanupService;

    @Autowired
    private FileCleanupRepository fileCleanupRepository;

    @Autowired
    private IdentityCrypto identityCrypto;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("storage.local.root", () -> uploadRoot.toString());
        registry.add("storage.local.cleanup-enabled", () -> "false");
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
            assertEquals(47, result.getInt("table_count"));
            assertEquals(result.getInt("table_count"), result.getInt("described_table_count"));
            assertEquals(83, result.getInt("described_column_count"));
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

    @Test
    void caseListPaginatesOnlyOwnedActiveSummaries() throws Exception {
        var owner = registerTestUser();
        var other = registerTestUser();
        UUID ownerId = tokenUserId(owner);
        mockMvc.perform(get("/api/v1/cases").header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(20))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        UUID first = createHttpCase(owner, ownerId);
        UUID second = createHttpCase(owner, ownerId);
        UUID third = createHttpCase(owner, ownerId);
        createHttpCase(other, ownerId);
        mockMvc.perform(get("/api/v1/cases").header("Authorization", "Bearer " + owner.accessToken())
                        .param("pageSize", "2").param("userId", tokenUserId(other).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].id").value(third.toString()))
                .andExpect(jsonPath("$.data.items[1].id").value(second.toString()))
                .andExpect(jsonPath("$.data.items[0].originalStatement").doesNotExist())
                .andExpect(jsonPath("$.data.hasNext").value(true));
        mockMvc.perform(get("/api/v1/cases").header("Authorization", "Bearer " + owner.accessToken())
                        .param("page", "2").param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(first.toString()))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        mockMvc.perform(get("/api/v1/cases").header("Authorization", "Bearer " + owner.accessToken())
                        .param("page", "3").param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0))
                .andExpect(jsonPath("$.data.hasNext").value(false));
        mockMvc.perform(patch("/api/v1/cases/{caseId}", first)
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originalStatement\":\"최근 수정 사건\",\"expectedVersion\":1}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/cases").header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(first.toString()));
        // 한 문장에서 갱신하면 세 사건의 updated_at이 같아진다.
        try (var connection = adminConnection();
             var statement = connection.prepareStatement(
                     "UPDATE casework.cases SET title = title WHERE owner_user_id = ?")) {
            statement.setObject(1, ownerId);
            assertEquals(3, statement.executeUpdate());
        }
        var orderedIds = List.of(first.toString(), second.toString(), third.toString()).stream()
                .sorted(java.util.Comparator.reverseOrder()).toList();
        mockMvc.perform(get("/api/v1/cases").header("Authorization", "Bearer " + owner.accessToken())
                        .param("pageSize", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(orderedIds.get(0)))
                .andExpect(jsonPath("$.data.items[1].id").value(orderedIds.get(1)))
                .andExpect(jsonPath("$.data.items[2].id").value(orderedIds.get(2)))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void deletionRollsBackWhenOutboxWriteFails() throws Exception {
        var owner = registerTestUser();
        UUID ownerId = tokenUserId(owner);
        UUID caseId = createHttpCase(owner, ownerId);
        String before = caseSnapshot(caseId);
        // 삭제 이벤트 쓰기만 실패하도록 멱등 키 충돌을 만든다.
        try (var connection = adminConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO ops.outbox_events(event_type, aggregate_type, aggregate_id,
                         idempotency_key, retention_expires_at)
                     VALUES ('TEST_CONFLICT', 'case', ?, ?, now() + interval '1 day')
                     """)) {
            statement.setObject(1, caseId);
            statement.setString(2, "case-deleted:" + caseId);
            statement.executeUpdate();
        }
        authenticate(ownerId);
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> service.deleteCase(caseId));
        assertEquals(before, caseSnapshot(caseId));
        assertEquals(2, outboxCount(caseId));
        assertEquals(caseId, service.getCase(caseId).id());
    }

    @Test
    void deletionFunctionRejectsMissingUserAndAuthRole() throws Exception {
        var owner = registerTestUser();
        UUID caseId = createHttpCase(owner, tokenUserId(owner));
        String before = caseSnapshot(caseId);
        assertEquals(null, jdbcTemplate.queryForObject(
                "SELECT casework.soft_delete_case(?)", Integer.class, caseId));
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "legal_ai_auth", "auth_test");
             var statement = connection.prepareStatement("SELECT casework.soft_delete_case(?)")) {
            statement.setObject(1, caseId);
            assertEquals("42501", assertThrows(SQLException.class, statement::executeQuery).getSQLState());
        }
        assertEquals(before, caseSnapshot(caseId));
    }

    @Test
    void databaseDeletionSerializesConcurrentRequests() throws Exception {
        var owner = registerTestUser();
        UUID ownerId = tokenUserId(owner);
        UUID caseId = createHttpCase(owner, ownerId);
        Callable<Integer> deleteRequest = () -> {
            // 서로 다른 실제 DB 연결에서 동시에 갱신해 함수의 행 잠금을 검증한다.
            try (var connection = appConnection()) {
                connection.setAutoCommit(false);
                try (var scope = connection.prepareStatement("SELECT set_config('app.user_id', ?, true)")) {
                    scope.setString(1, ownerId.toString());
                    scope.execute();
                }
                try (var statement = connection.prepareStatement("SELECT casework.soft_delete_case(?)")) {
                    statement.setObject(1, caseId);
                    Integer version;
                    try (var result = statement.executeQuery()) {
                        result.next();
                        version = result.getObject(1, Integer.class);
                    }
                    connection.commit();
                    return version;
                } finally {
                    connection.rollback();
                }
            }
        };
        var results = concurrently(List.of(deleteRequest, deleteRequest));
        assertEquals(1, results.stream().filter(java.util.Objects::isNull).count());
        assertEquals(1, results.stream().filter(Integer.valueOf(2)::equals).count());
    }

    @Test
    void caseListRejectsInvalidPaginationAndMissingAuthentication() throws Exception {
        var owner = registerTestUser();
        for (String query : List.of("page=0", "page=-1", "page=10001", "page=abc",
                "pageSize=0", "pageSize=101", "pageSize=abc")) {
            mockMvc.perform(get("/api/v1/cases?" + query)
                            .header("Authorization", "Bearer " + owner.accessToken()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("COMMON_001"));
        }
        mockMvc.perform(get("/api/v1/cases")).andExpect(status().isUnauthorized());
    }

    @Test
    void softDeleteHidesCaseAndChildrenAndSchedulesOneDeletionEvent() throws Exception {
        var owner = registerTestUser();
        var other = registerTestUser();
        UUID ownerId = tokenUserId(owner);
        UUID caseId = createHttpCase(owner, ownerId);
        UUID fileId = UUID.randomUUID();
        try (var connection = adminConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO casework.files(id, case_id, uploaded_by, file_type, original_name,
                         mime_type, size_bytes, sha256) VALUES (?, ?, ?, 'document', 'contract.pdf',
                         'application/pdf', 10, ?)
                     """)) {
            statement.setObject(1, fileId);
            statement.setObject(2, caseId);
            statement.setObject(3, ownerId);
            statement.setString(4, "c".repeat(64));
            statement.executeUpdate();
        }
        String before = caseSnapshot(caseId);
        mockMvc.perform(delete("/api/v1/cases/{caseId}", caseId)).andExpect(status().isUnauthorized());
        for (UUID target : List.of(caseId, UUID.randomUUID())) {
            mockMvc.perform(delete("/api/v1/cases/{caseId}", target)
                            .header("Authorization", "Bearer " + other.accessToken()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("CASE_001"));
        }
        assertEquals(before, caseSnapshot(caseId));
        assertEquals(1, outboxCount(caseId));
        mockMvc.perform(delete("/api/v1/cases/{caseId}", caseId)
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/cases/{caseId}", caseId)
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/v1/cases/{caseId}", caseId)
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"originalStatement\":\"삭제 후 수정\",\"expectedVersion\":2}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/cases/{caseId}", caseId)
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/cases").header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
        assertEquals(2, outboxCount(caseId));
        try (var connection = adminConnection();
             var statement = connection.prepareStatement("""
                     SELECT deleted_at IS NOT NULL AS deleted,
                            hard_delete_after = deleted_at + interval '7 days' AS grace_period,
                            version_no,
                            (SELECT count(*) FROM ops.outbox_events
                             WHERE aggregate_id = c.id AND event_type = 'CASE_DELETED'
                               AND payload->>'version' = '2') AS events
                     FROM casework.cases c WHERE id = ?
                     """)) {
            statement.setObject(1, caseId);
            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertTrue(result.getBoolean("deleted"));
                assertTrue(result.getBoolean("grace_period"));
                assertEquals(2, result.getInt("version_no"));
                assertEquals(1, result.getInt("events"));
            }
        }
        try (var connection = appConnection()) {
            connection.setAutoCommit(false);
            try (var scope = connection.prepareStatement("SELECT set_config('app.user_id', ?, true)")) {
                scope.setString(1, ownerId.toString());
                scope.execute();
            }
            try (var statement = connection.prepareStatement("SELECT count(*) FROM casework.files WHERE id = ?")) {
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

    @Test
    void uploadedFileMetadataIsOwnedAndStorageDetailsArePrivate() throws Exception {
        var owner = registerTestUser();
        var other = registerTestUser();
        UUID caseId = createHttpCase(owner, tokenUserId(owner));
        byte[] bytes = testPng();
        UUID fileId = uploadTestFile(owner, caseId, new MockMultipartFile("file", "증거.png", "image/png", bytes));
        assertTrue(Files.exists(uploadRoot.resolve(fileId + ".upload")));
        assertEquals(1, outboxCount(fileId));
        mockMvc.perform(get("/api/v1/cases/{caseId}/files/{fileId}", caseId, fileId)
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageCount").value(1))
                .andExpect(jsonPath("$.data.sizeBytes").value(bytes.length))
                .andExpect(jsonPath("$.data.storageBucket").doesNotExist())
                .andExpect(jsonPath("$.data.objectKey").doesNotExist())
                .andExpect(jsonPath("$.data.path").doesNotExist());
        mockMvc.perform(get("/api/v1/cases/{caseId}/files/{fileId}", caseId, fileId)
                        .header("Authorization", "Bearer " + other.accessToken()))
                .andExpect(status().isNotFound());
        UUID otherCase = createHttpCase(owner, tokenUserId(owner));
        mockMvc.perform(get("/api/v1/cases/{caseId}/files/{fileId}", otherCase, fileId)
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isNotFound());
        mockMvc.perform(multipart("/api/v1/cases/{caseId}/files", caseId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .file(new MockMultipartFile("file", "증거.png", "image/png", bytes))
                        .header("Authorization", "Bearer " + other.accessToken()))
                .andExpect(status().isNotFound());
        mockMvc.perform(multipart("/api/v1/cases/{caseId}/files", caseId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .file(new MockMultipartFile("file", "증거.png", "image/png", bytes)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(multipart("/api/v1/cases/{caseId}/files", caseId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isBadRequest());
        try (var connection = adminConnection();
             var statement = connection.prepareStatement("SELECT sha256, uploaded_by FROM casework.files WHERE id = ?")) {
            statement.setObject(1, fileId);
            try (var row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals(java.util.HexFormat.of().formatHex(
                        java.security.MessageDigest.getInstance("SHA-256").digest(bytes)), row.getString(1));
                assertEquals(tokenUserId(owner), row.getObject(2, UUID.class));
            }
        }
    }

    @Test
    void invalidUploadsLeaveNoOriginalOrMetadata() throws Exception {
        var owner = registerTestUser();
        UUID caseId = createHttpCase(owner, tokenUserId(owner));
        long before;
        try (var files = Files.list(uploadRoot)) {
            before = files.count();
        }
        for (var file : List.of(
                new MockMultipartFile("file", "../증거.png", "image/png", testPng()),
                new MockMultipartFile("file", "증거.png", "application/pdf", testPng()),
                new MockMultipartFile("file", "증거.png", "image/png", new byte[20]),
                new MockMultipartFile("file", "증거.pdf", "application/pdf", "%PDF-1.7 broken".getBytes()),
                new MockMultipartFile("file", "증거.pdf", "application/pdf", testPdf(31)),
                new MockMultipartFile("file", "증거.pdf", "application/pdf", testPdf(0)),
                new MockMultipartFile("file", "증거.png", "image/png", new byte[0]))) {
            mockMvc.perform(multipart("/api/v1/cases/{caseId}/files", caseId)
                        .header("Idempotency-Key", UUID.randomUUID().toString()).file(file)
                            .header("Authorization", "Bearer " + owner.accessToken()))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(multipart("/api/v1/cases/{caseId}/files", caseId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .file(new MockMultipartFile("file", "증거.png", "image/png", new byte[20 * 1024 * 1024 + 1]))
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.code").value("FILE_003"));
        try (var files = Files.list(uploadRoot)) {
            assertEquals(before, files.count());
        }
        try (var connection = adminConnection();
             var statement = connection.prepareStatement("SELECT count(*) FROM casework.files WHERE case_id = ?")) {
            statement.setObject(1, caseId);
            try (var row = statement.executeQuery()) {
                row.next();
                assertEquals(0, row.getInt(1));
            }
        }
    }

    @Test
    void pdfPageBoundaryAndCaseFileCountAreEnforced() throws Exception {
        var owner = registerTestUser();
        UUID caseId = createHttpCase(owner, tokenUserId(owner));
        UUID pdfId = uploadTestFile(owner, caseId,
                new MockMultipartFile("file", "계약.pdf", "application/pdf", testPdf(30)));
        mockMvc.perform(get("/api/v1/cases/{caseId}/files/{fileId}", caseId, pdfId)
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.pageCount").value(30));
        for (int i = 1; i < 10; i++) {
            uploadTestFile(owner, caseId, new MockMultipartFile("file", "증거.png", "image/png", testPng()));
        }
        mockMvc.perform(multipart("/api/v1/cases/{caseId}/files", caseId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .file(new MockMultipartFile("file", "증거.png", "image/png", testPng()))
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_005"));
    }

    @Test
    void caseTotalBytesAreLimitedIndependentlyOfFileCount() throws Exception {
        var owner = registerTestUser();
        UUID caseId = createHttpCase(owner, tokenUserId(owner));
        for (int i = 0; i < 5; i++) {
            uploadTestFile(owner, caseId, new MockMultipartFile("file", "증거.png", "image/png", testPng()));
        }
        // 큰 파일 다섯 개의 메타데이터로 합계 100MiB 경계만 재현한다.
        try (var connection = adminConnection();
             var statement = connection.prepareStatement(
                     "UPDATE casework.files SET size_bytes = 20971520 WHERE case_id = ?")) {
            statement.setObject(1, caseId);
            assertEquals(5, statement.executeUpdate());
        }
        mockMvc.perform(multipart("/api/v1/cases/{caseId}/files", caseId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .file(new MockMultipartFile("file", "증거.png", "image/png", testPng()))
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_005"));
    }

    @Test
    void expiredAndDeletedCaseOriginalsArePurgedAndOrphansAreCleaned() throws Exception {
        var owner = registerTestUser();
        UUID caseId = createHttpCase(owner, tokenUserId(owner));
        UUID expiredId = uploadTestFile(owner, caseId,
                new MockMultipartFile("file", "증거.png", "image/png", testPng()));
        makeFilePurgeDue(expiredId);
        fileCleanupService.cleanup();
        assertTrue(!Files.exists(uploadRoot.resolve(expiredId + ".upload")));
        assertPurgeState(expiredId, "purged", 1);
        fileCleanupService.cleanup();
        assertPurgeState(expiredId, "purged", 1);

        UUID deletedId = uploadTestFile(owner, caseId,
                new MockMultipartFile("file", "증거.png", "image/png", testPng()));
        mockMvc.perform(delete("/api/v1/cases/{caseId}", caseId)
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/cases/{caseId}/files/{fileId}", caseId, deletedId)
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isNotFound());
        fileCleanupService.cleanup();
        assertTrue(!Files.exists(uploadRoot.resolve(deletedId + ".upload")));
        assertPurgeState(deletedId, "purged", 1);

        Path orphan = Files.createFile(uploadRoot.resolve(UUID.randomUUID() + ".upload"));
        Files.setLastModifiedTime(orphan, FileTime.from(Instant.now().minusSeconds(25 * 3600)));
        Path unrelated = Files.createFile(uploadRoot.resolve("unrelated-" + UUID.randomUUID() + ".txt"));
        Files.setLastModifiedTime(unrelated, FileTime.from(Instant.now().minusSeconds(25 * 3600)));
        fileCleanupService.cleanup();
        assertTrue(!Files.exists(orphan));
        assertTrue(Files.exists(unrelated));
    }

    @Test
    void purgeFailuresStopAfterFiveAttempts() throws Exception {
        var owner = registerTestUser();
        UUID caseId = createHttpCase(owner, tokenUserId(owner));
        UUID fileId = uploadTestFile(owner, caseId,
                new MockMultipartFile("file", "증거.png", "image/png", testPng()));
        for (int attempt = 1; attempt <= 5; attempt++) {
            makeFilePurgeDue(fileId);
            assertTrue(fileCleanupRepository.candidates().contains(fileId));
            fileCleanupRepository.record(fileId, false);
            assertPurgeState(fileId, attempt == 5 ? "failed" : "retrying", attempt);
            assertTrue(!fileCleanupRepository.candidates().contains(fileId));
        }
        makeFilePurgeDue(fileId);
        assertTrue(!fileCleanupRepository.candidates().contains(fileId));
        fileCleanupRepository.record(fileId, false);
        assertPurgeState(fileId, "failed", 5);
        assertTrue(Files.exists(uploadRoot.resolve(fileId + ".upload")));
    }

    @Test
    void malwareRejectionAndScannerOutageRollbackOriginalAndDatabase() throws Exception {
        var owner = registerTestUser();
        UUID caseId = createHttpCase(owner, tokenUserId(owner));
        long before;
        try (var files = Files.list(uploadRoot)) {
            before = files.count();
        }
        for (var code : List.of(ErrorCode.UNSAFE_FILE, ErrorCode.MALWARE_SCAN_UNAVAILABLE)) {
            org.mockito.Mockito.doThrow(new BusinessException(code))
                    .when(malwareScanner).assertClean(org.mockito.ArgumentMatchers.any(Path.class));
            mockMvc.perform(multipart("/api/v1/cases/{caseId}/files", caseId)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                            .file(new MockMultipartFile("file", "증거.png", "image/png", testPng()))
                            .header("Authorization", "Bearer " + owner.accessToken()))
                    .andExpect(status().is(code.status().value()))
                    .andExpect(jsonPath("$.code").value(code.code()));
        }
        try (var files = Files.list(uploadRoot)) {
            assertEquals(before, files.count());
        }
        try (var connection = adminConnection();
             var statement = connection.prepareStatement("""
                     SELECT (SELECT count(*) FROM casework.files WHERE case_id = ?),
                         (SELECT count(*) FROM ops.outbox_events WHERE event_type = 'FILE_UPLOADED'
                          AND payload->>'caseId' = ?)
                     """)) {
            statement.setObject(1, caseId);
            statement.setString(2, caseId.toString());
            try (var row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals(0, row.getInt(1));
                assertEquals(0, row.getInt(2));
            }
        }
    }

    @Test
    void successfulScanRecordsProviderAndTimestamp() throws Exception {
        var owner = registerTestUser();
        UUID caseId = createHttpCase(owner, tokenUserId(owner));
        UUID fileId = uploadTestFile(owner, caseId,
                new MockMultipartFile("file", "증거.png", "image/png", testPng()));
        org.mockito.Mockito.verify(malwareScanner).assertClean(uploadRoot.resolve(fileId + ".upload"));
        try (var connection = adminConnection();
             var statement = connection.prepareStatement("""
                     SELECT malware_status, malware_scan_provider, malware_scanned_at
                     FROM casework.files WHERE id = ?
                     """)) {
            statement.setObject(1, fileId);
            try (var row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals("clean", row.getString(1));
                assertEquals("clamav", row.getString(2));
                assertTrue(row.getTimestamp(3) != null);
            }
        }
    }

    @Test
    void freeIsLimitedToTenAndPaidHasNoDailyCountOrByteCap() throws Exception {
        UUID free = tokenUserId(registerTestUser());
        for (int i = 0; i < 10; i++) {
            assertEquals("OK", consumeUploadAs(free, 1));
        }
        assertEquals("PLAN_LIMIT", consumeUploadAs(free, 1));
        UUID paid = tokenUserId(registerTestUser());
        setPaidPlan(paid, false);
        seedUploadUsage(paid, 299, 299, 0);
        assertEquals("OK", consumeUploadAs(paid, 1));
        assertEquals("OK", consumeUploadAs(paid, 1));
        UUID volumeLimited = tokenUserId(registerTestUser());
        setPaidPlan(volumeLimited, false);
        seedUploadUsage(volumeLimited, 1, 1073741823L, 0);
        assertEquals("OK", consumeUploadAs(volumeLimited, 1));
        assertEquals("OK", consumeUploadAs(volumeLimited, 20971520));
    }

    @Test
    void expiredPaidPlanFallsBackToFreeAndUtcDayResetsUsage() throws Exception {
        UUID user = tokenUserId(registerTestUser());
        setPaidPlan(user, true);
        seedUploadUsage(user, 10, 10, 0);
        assertEquals("PLAN_LIMIT", consumeUploadAs(user, 1));
        UUID nextDay = tokenUserId(registerTestUser());
        seedUploadUsage(nextDay, 300, 1073741824L, -1);
        assertEquals("OK", consumeUploadAs(nextDay, 1));
    }

    @Test
    void concurrentReservationsCannotExceedFreePlan() throws Exception {
        UUID user = tokenUserId(registerTestUser());
        seedUploadUsage(user, 9, 9, 0);
        Callable<String> consume = () -> consumeUploadAs(user, 1);
        var results = concurrently(List.of(consume, consume, consume, consume));
        assertEquals(1, results.stream().filter("OK"::equals).count());
        assertEquals(3, results.stream().filter("PLAN_LIMIT"::equals).count());
    }

    @Test
    void applicationCannotUpgradePlanOrReadAnotherUsersUsage() throws Exception {
        UUID owner = tokenUserId(registerTestUser());
        UUID other = tokenUserId(registerTestUser());
        setPaidPlan(other, false);
        seedUploadUsage(other, 1, 1, 0);
        try (var connection = appConnection()) {
            setDatabaseUser(connection, owner);
            try (var statement = connection.createStatement()) {
                assertEquals("42501", assertThrows(SQLException.class, () -> statement.executeUpdate(
                        "INSERT INTO identity.user_plans VALUES ('" + owner + "', 'PAID', now() + interval '1 day')"))
                        .getSQLState());
            }
            connection.rollback();
            setDatabaseUser(connection, owner);
            try (var statement = connection.createStatement();
                 var row = statement.executeQuery("SELECT count(*) FROM identity.user_plans")) {
                row.next();
                assertEquals(0, row.getInt(1));
            }
            try (var statement = connection.createStatement();
                 var row = statement.executeQuery("SELECT count(*) FROM casework.daily_upload_usage")) {
                row.next();
                assertEquals(0, row.getInt(1));
            }
            connection.rollback();
        }
    }

    @Test
    void duplicateUploadReturnsSameFileWithoutRescanOrExtraQuota() throws Exception {
        var owner = registerTestUser();
        UUID user = tokenUserId(owner);
        UUID caseId = createHttpCase(owner, user);
        UUID key = UUID.randomUUID();
        var file = new MockMultipartFile("file", "증거.png", "image/png", testPng());
        String first = uploadWithKey(owner, caseId, key, file).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = uploadWithKey(owner, caseId, key, file).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(first).path("data").path("id").asString();
        assertEquals(id, objectMapper.readTree(second).path("data").path("id").asString());
        org.mockito.Mockito.verify(malwareScanner, org.mockito.Mockito.times(1))
                .assertClean(org.mockito.ArgumentMatchers.any(Path.class));
        assertEquals(1, outboxCount(UUID.fromString(id)));
        assertUsageAttempts(user, 1);
        uploadWithKey(owner, caseId, key, new MockMultipartFile("file", "다른이름.png", "image/png", testPng()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_010"));
        UUID otherCase = createHttpCase(owner, user);
        uploadWithKey(owner, otherCase, key, file).andExpect(status().isConflict());
        assertUsageAttempts(user, 1);
    }

    @Test
    void failedUploadReplayDoesNotRepeatScannerOrRefundSafetyUsage() throws Exception {
        var owner = registerTestUser();
        UUID user = tokenUserId(owner);
        UUID caseId = createHttpCase(owner, user);
        UUID key = UUID.randomUUID();
        var file = new MockMultipartFile("file", "증거.png", "image/png", testPng());
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.MALWARE_SCAN_UNAVAILABLE))
                .when(malwareScanner).assertClean(org.mockito.ArgumentMatchers.any(Path.class));
        for (int i = 0; i < 2; i++) {
            uploadWithKey(owner, caseId, key, file).andExpect(status().isServiceUnavailable());
        }
        assertUsageAttempts(user, 1);
        org.mockito.Mockito.verify(malwareScanner, org.mockito.Mockito.times(1))
                .assertClean(org.mockito.ArgumentMatchers.any(Path.class));
    }

    @Test
    void concurrentSameKeyUploadsCreateOnlyOneFile() throws Exception {
        var owner = registerTestUser();
        UUID user = tokenUserId(owner);
        UUID caseId = createHttpCase(owner, user);
        UUID key = UUID.randomUUID();
        byte[] png = testPng();
        Callable<Integer> upload = () -> uploadWithKey(owner, caseId, key,
                new MockMultipartFile("file", "증거.png", "image/png", png))
                .andReturn().getResponse().getStatus();
        var results = concurrently(List.of(upload, upload));
        assertTrue(results.contains(201));
        assertTrue(results.stream().allMatch(code -> code == 201 || code == 409));
        uploadWithKey(owner, caseId, key, new MockMultipartFile("file", "증거.png", "image/png", png))
                .andExpect(status().isCreated());
        org.mockito.Mockito.verify(malwareScanner, org.mockito.Mockito.times(1))
                .assertClean(org.mockito.ArgumentMatchers.any(Path.class));
        assertUsageAttempts(user, 1);
    }

    @Test
    void uploadHeaderAndFreePlanLimitAreEnforcedButPaidCanContinue() throws Exception {
        var owner = registerTestUser();
        UUID user = tokenUserId(owner);
        UUID caseId = createHttpCase(owner, user);
        var file = new MockMultipartFile("file", "증거.png", "image/png", testPng());
        mockMvc.perform(multipart("/api/v1/cases/{caseId}/files", caseId).file(file)
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(multipart("/api/v1/cases/{caseId}/files", caseId).file(file)
                        .header("Idempotency-Key", "invalid")
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isBadRequest());
        seedUploadUsage(user, 10, 10, 0);
        uploadWithKey(owner, caseId, UUID.randomUUID(), file)
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("FILE_009"));
        setPaidPlan(user, false);
        seedUploadUsage(user, 300, 300, 0);
        uploadWithKey(owner, caseId, UUID.randomUUID(), file)
                .andExpect(status().isCreated());
        org.mockito.Mockito.verify(malwareScanner, org.mockito.Mockito.times(1))
                .assertClean(org.mockito.ArgumentMatchers.any(Path.class));
    }

    private org.springframework.test.web.servlet.ResultActions uploadWithKey(
            AuthTokenResponse owner, UUID caseId, UUID key, MockMultipartFile file) throws Exception {
        return mockMvc.perform(multipart("/api/v1/cases/{caseId}/files", caseId).file(file)
                .header("Idempotency-Key", key.toString())
                .header("Authorization", "Bearer " + owner.accessToken()));
    }

    private void setPaidPlan(UUID user, boolean expired) throws SQLException {
        try (var connection = adminConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO identity.user_plans VALUES (?, 'PAID', now() + make_interval(days => ?))
                     ON CONFLICT (user_id) DO UPDATE SET plan_code = 'PAID', expires_at = EXCLUDED.expires_at
                     """)) {
            statement.setObject(1, user);
            statement.setInt(2, expired ? -1 : 1);
            statement.executeUpdate();
        }
    }

    private void seedUploadUsage(UUID user, int attempts, long bytes, int dayOffset) throws SQLException {
        try (var connection = adminConnection();
             var statement = connection.prepareStatement("""
                     INSERT INTO casework.daily_upload_usage VALUES (?, (now() AT TIME ZONE 'UTC')::date + ?, ?, ?)
                     ON CONFLICT (user_id, usage_date) DO UPDATE
                     SET attempts = EXCLUDED.attempts, size_bytes = EXCLUDED.size_bytes
                     """)) {
            statement.setObject(1, user);
            statement.setInt(2, dayOffset);
            statement.setInt(3, attempts);
            statement.setLong(4, bytes);
            statement.executeUpdate();
        }
    }

    private String consumeUploadAs(UUID user, long bytes) throws SQLException {
        try (var connection = appConnection()) {
            setDatabaseUser(connection, user);
            try (var statement = connection.prepareStatement("SELECT casework.consume_daily_upload(?)")) {
                statement.setLong(1, bytes);
                try (var row = statement.executeQuery()) {
                    row.next();
                    String result = row.getString(1);
                    connection.commit();
                    return result;
                }
            }
        }
    }

    private void setDatabaseUser(Connection connection, UUID user) throws SQLException {
        connection.setAutoCommit(false);
        try (var statement = connection.prepareStatement("SELECT set_config('app.user_id', ?, true)")) {
            statement.setString(1, user.toString());
            statement.execute();
        }
    }

    private void assertUsageAttempts(UUID user, int expected) throws SQLException {
        try (var connection = adminConnection();
             var statement = connection.prepareStatement("""
                     SELECT attempts FROM casework.daily_upload_usage
                     WHERE user_id = ? AND usage_date = (now() AT TIME ZONE 'UTC')::date
                     """)) {
            statement.setObject(1, user);
            try (var row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals(expected, row.getInt(1));
            }
        }
    }

    private void makeFilePurgeDue(UUID fileId) throws SQLException {
        try (var connection = adminConnection();
             var statement = connection.prepareStatement("""
                     UPDATE casework.files SET storage_expires_at = now() - interval '1 second',
                         purge_next_retry_at = now() - interval '1 second' WHERE id = ?
                     """)) {
            statement.setObject(1, fileId);
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void assertPurgeState(UUID fileId, String state, int attempts) throws SQLException {
        try (var connection = adminConnection();
             var statement = connection.prepareStatement("""
                     SELECT purge_status, purge_attempt_count, storage_bucket, object_key, purged_at
                     FROM casework.files WHERE id = ?
                     """)) {
            statement.setObject(1, fileId);
            try (var row = statement.executeQuery()) {
                assertTrue(row.next());
                assertEquals(state, row.getString(1));
                assertEquals(attempts, row.getInt(2));
                if (state.equals("purged")) {
                    assertEquals(null, row.getString(3));
                    assertEquals(null, row.getString(4));
                    assertTrue(row.getTimestamp(5) != null);
                }
            }
        }
    }

    private UUID uploadTestFile(AuthTokenResponse owner, UUID caseId, MockMultipartFile file) throws Exception {
        String response = mockMvc.perform(multipart("/api/v1/cases/{caseId}/files", caseId)
                        .header("Idempotency-Key", UUID.randomUUID().toString()).file(file)
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.lifecycleStatus").value("UPLOADED"))
                .andExpect(jsonPath("$.data.malwareStatus").value("clean"))
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(response).path("data").path("id").asString());
    }

    private byte[] testPng() throws Exception {
        var output = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(
                2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }

    private byte[] testPdf(int pages) throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage());
            }
            document.save(output);
            return output.toByteArray();
        }
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

    @Test
    void chatCommitsQuestionBeforeGenerationAndReplaysOnlyStoredAnswer() throws Exception {
        var owner = registerTestUser();
        UUID caseId = createHttpCase(owner, tokenUserId(owner));
        UUID key = UUID.randomUUID();
        org.mockito.Mockito.when(openAiChat.generate(org.mockito.ArgumentMatchers.anyList())).thenAnswer(call -> {
            // 앱 풀 크기가 1이어도 API 호출 중 DB를 사용할 수 있어야 한다.
            assertEquals(1, jdbcTemplate.queryForObject("SELECT 1", Integer.class));
            try (var connection = adminConnection(); var statement = connection.prepareStatement(
                    "SELECT question, answer, status FROM casework.chat_turns WHERE case_id = ?")) {
                statement.setObject(1, caseId);
                try (var rows = statement.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals("누수가 발생했어요", rows.getString(1));
                    org.junit.jupiter.api.Assertions.assertNull(rows.getString(2));
                    assertEquals("RUNNING", rows.getString(3));
                }
            }
            return chatAnswer();
        });
        sendChat(owner, caseId, key, "누수가 발생했어요")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.answer").value("언제 발생했나요?"))
                .andExpect(jsonPath("$.data.status").doesNotExist());
        sendChat(owner, caseId, key, "누수가 발생했어요").andExpect(status().isOk());
        chatHistory(owner, caseId).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].retryAllowed").value(false));
        org.mockito.Mockito.verify(openAiChat, org.mockito.Mockito.times(1)).generate(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void chatEnforcesOwnershipForSendListAndRetryAndDatabaseRls() throws Exception {
        var owner = registerTestUser();
        var other = registerTestUser();
        UUID caseId = createHttpCase(owner, tokenUserId(owner));
        UUID key = UUID.randomUUID();
        stubChat();
        sendChat(owner, caseId, key, "질문").andExpect(status().isOk());
        sendChat(other, caseId, UUID.randomUUID(), "질문").andExpect(status().isNotFound());
        chatHistory(other, caseId).andExpect(status().isNotFound());
        retryChat(other, caseId, key, 1).andExpect(status().isNotFound());
        try (var connection = appConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("SELECT set_config('app.user_id', '" + tokenUserId(other) + "', true)");
                try (var rows = statement.executeQuery("SELECT count(*) FROM casework.chat_turns")) {
                    rows.next();
                    assertEquals(0, rows.getInt(1));
                }
            }
        }
        org.mockito.Mockito.verify(openAiChat, org.mockito.Mockito.times(1)).generate(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void chatFailureNeedsExplicitIdempotentRetryWithoutDuplicatingQuestion() throws Exception {
        var owner = registerTestUser();
        UUID caseId = createHttpCase(owner, tokenUserId(owner));
        UUID key = UUID.randomUUID();
        org.mockito.Mockito.when(openAiChat.generate(org.mockito.ArgumentMatchers.anyList()))
                .thenThrow(new BusinessException(ErrorCode.CHAT_GENERATION_FAILED)).thenReturn(chatAnswer());
        sendChat(owner, caseId, key, "질문").andExpect(status().isServiceUnavailable());
        sendChat(owner, caseId, key, "질문").andExpect(status().isServiceUnavailable());
        chatHistory(owner, caseId).andExpect(jsonPath("$.data.items[0].answer").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].retryAllowed").value(true));
        retryChat(owner, caseId, key, 1).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.attempt").value(2));
        retryChat(owner, caseId, key, 1).andExpect(status().isOk());
        chatHistory(owner, caseId).andExpect(jsonPath("$.data.items.length()").value(1));
        org.mockito.Mockito.verify(openAiChat, org.mockito.Mockito.times(2)).generate(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void chatValidatesKeysInputAndRetryGeneration() throws Exception {
        var owner = registerTestUser();
        UUID caseId = createHttpCase(owner, tokenUserId(owner));
        UUID key = UUID.randomUUID();
        stubChat();
        sendChat(owner, caseId, key, "질문").andExpect(status().isOk());
        sendChat(owner, caseId, key, "다른 질문").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CHAT_001"));
        sendChat(owner, caseId, UUID.randomUUID(), " ").andExpect(status().isBadRequest());
        sendChat(owner, caseId, UUID.randomUUID(), "x".repeat(4001)).andExpect(status().isBadRequest());
        retryChat(owner, caseId, key, 3).andExpect(status().isConflict());
        retryChat(owner, caseId, key, 0).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/cases/{id}/chat/turns", caseId)
                .header("Authorization", "Bearer " + owner.accessToken()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"질문\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/cases/{id}/chat/turns", caseId)
                .header("Authorization", "Bearer " + owner.accessToken()).header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content("{\"content\":"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void chatConcurrentRequestDoesNotRegenerateOrHoldDatabaseConnection() throws Exception {
        var owner = registerTestUser();
        UUID caseId = createHttpCase(owner, tokenUserId(owner));
        UUID secondCase = createHttpCase(owner, tokenUserId(owner));
        UUID key = UUID.randomUUID();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        org.mockito.Mockito.when(openAiChat.generate(org.mockito.ArgumentMatchers.anyList())).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return chatAnswer();
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> sendChat(owner, caseId, key, "질문").andReturn().getResponse().getStatus());
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                sendChat(owner, caseId, key, "질문").andExpect(status().isTooManyRequests());
                sendChat(owner, secondCase, UUID.randomUUID(), "질문").andExpect(status().isTooManyRequests());
                chatHistory(owner, caseId).andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.items[0].answer").doesNotExist())
                        .andExpect(jsonPath("$.data.items[0].retryAllowed").value(false));
            } finally { release.countDown(); }
            assertEquals(200, first.get(10, TimeUnit.SECONDS));
        }
        org.mockito.Mockito.verify(openAiChat, org.mockito.Mockito.times(1)).generate(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void chatRetriesDatabaseSaveWithoutCallingModelAgain() throws Exception {
        var owner = registerTestUser();
        UUID caseId = createHttpCase(owner, tokenUserId(owner));
        stubChat();
        org.mockito.Mockito.doThrow(new org.springframework.dao.TransientDataAccessResourceException("비밀 SQL 본문"))
                .doCallRealMethod().when(chatRepository).complete(org.mockito.ArgumentMatchers.eq(caseId),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());
        sendChat(owner, caseId, UUID.randomUUID(), "질문").andExpect(status().isOk());
        org.mockito.Mockito.verify(openAiChat, org.mockito.Mockito.times(1)).generate(org.mockito.ArgumentMatchers.anyList());
        org.mockito.Mockito.verify(chatRepository, org.mockito.Mockito.times(2)).complete(org.mockito.ArgumentMatchers.eq(caseId),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void chatDoesNotReturnAnswerWhenDatabaseSaveFails() throws Exception {
        var owner = registerTestUser();
        UUID caseId = createHttpCase(owner, tokenUserId(owner));
        stubChat();
        org.mockito.Mockito.doThrow(new org.springframework.dao.TransientDataAccessResourceException("비밀 SQL 본문"))
                .when(chatRepository).complete(org.mockito.ArgumentMatchers.eq(caseId), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());
        sendChat(owner, caseId, UUID.randomUUID(), "질문").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CHAT_005")).andExpect(jsonPath("$.data").doesNotExist());
        chatHistory(owner, caseId).andExpect(jsonPath("$.data.items[0].answer").doesNotExist());
        org.mockito.Mockito.verify(openAiChat, org.mockito.Mockito.times(1)).generate(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void chatRecoversAbandonedAttemptOnlyOnExplicitRetryAndRejectsLateSave() throws Exception {
        var owner = registerTestUser();
        UUID userId = tokenUserId(owner);
        UUID caseId = createHttpCase(owner, userId);
        UUID key = UUID.randomUUID();
        try (var connection = adminConnection(); var statement = connection.prepareStatement("""
                INSERT INTO casework.chat_turns(case_id, id, owner_user_id, question, status, started_at)
                VALUES (?, ?, ?, '질문', 'RUNNING', now() - interval '4 minutes')
                """)) {
            statement.setObject(1, caseId); statement.setObject(2, key); statement.setObject(3, userId);
            statement.executeUpdate();
        }
        chatHistory(owner, caseId).andExpect(jsonPath("$.data.items[0].retryAllowed").value(true));
        org.mockito.Mockito.verifyNoInteractions(openAiChat);
        stubChat();
        retryChat(owner, caseId, key, 1).andExpect(status().isOk()).andExpect(jsonPath("$.data.attempt").value(2));
        try (var connection = appConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SELECT set_config('app.user_id', '" + userId + "', true)");
            assertEquals(0, statement.executeUpdate("UPDATE casework.chat_turns SET answer = '늦은 답변' "
                    + "WHERE id = '" + key + "' AND attempt = 1 AND status = 'RUNNING'"));
        }
    }

    @Test
    void chatHistoryIsBoundedAndOldFailedTurnCannotBeRetriedAfterNewQuestion() throws Exception {
        var owner = registerTestUser();
        UUID caseId = createHttpCase(owner, tokenUserId(owner));
        UUID failedKey = UUID.randomUUID();
        org.mockito.Mockito.when(openAiChat.generate(org.mockito.ArgumentMatchers.anyList()))
                .thenThrow(new BusinessException(ErrorCode.CHAT_GENERATION_FAILED)).thenReturn(chatAnswer());
        sendChat(owner, caseId, failedKey, "실패한 질문").andExpect(status().isServiceUnavailable());
        for (int i = 0; i < 7; i++) sendChat(owner, caseId, UUID.randomUUID(), "질문" + i).andExpect(status().isOk());
        retryChat(owner, caseId, failedKey, 1).andExpect(status().isConflict());
        org.mockito.ArgumentCaptor<List<kr.co.legalai.chat.entity.ChatInput>> capture = org.mockito.ArgumentCaptor.captor();
        org.mockito.Mockito.verify(openAiChat, org.mockito.Mockito.times(8)).generate(capture.capture());
        assertEquals(11, capture.getValue().size()); // 완성된 5턴(10메시지) + 현재 질문
        mockMvc.perform(get("/api/v1/cases/{id}/chat/turns", caseId).param("pageSize", "2")
                .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(jsonPath("$.data.items.length()").value(2)).andExpect(jsonPath("$.data.hasNext").value(true));
    }

    @Test
    void chatIsHiddenWhenCaseDeletedDuringGeneration() throws Exception {
        var owner = registerTestUser();
        UUID caseId = createHttpCase(owner, tokenUserId(owner));
        org.mockito.Mockito.when(openAiChat.generate(org.mockito.ArgumentMatchers.anyList())).thenAnswer(call -> {
            try (var connection = adminConnection(); var statement = connection.prepareStatement(
                    "UPDATE casework.cases SET deleted_at = now(), hard_delete_after = now() + interval '7 days' WHERE id = ?")) {
                statement.setObject(1, caseId); statement.executeUpdate();
            }
            return chatAnswer();
        });
        sendChat(owner, caseId, UUID.randomUUID(), "질문").andExpect(status().isNotFound());
        chatHistory(owner, caseId).andExpect(status().isNotFound());
        // 삭제된 사건의 내부 RUNNING 행이 새 사건을 영구 차단하지 않는다.
        stubChat();
        UUID next = createHttpCase(owner, tokenUserId(owner));
        sendChat(owner, next, UUID.randomUUID(), "질문").andExpect(status().isOk());
    }

    @Test
    void chatInstanceConcurrencyIsSeparateFromUserAndDailyPlanLimits() throws Exception {
        var firstUser = registerTestUser();
        var secondUser = registerTestUser();
        var thirdUser = registerTestUser();
        UUID firstCase = createHttpCase(firstUser, tokenUserId(firstUser));
        UUID secondCase = createHttpCase(secondUser, tokenUserId(secondUser));
        UUID thirdCase = createHttpCase(thirdUser, tokenUserId(thirdUser));
        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        org.mockito.Mockito.when(openAiChat.generate(org.mockito.ArgumentMatchers.anyList())).thenAnswer(call -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return chatAnswer();
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> sendChat(firstUser, firstCase, UUID.randomUUID(), "질문")
                    .andReturn().getResponse().getStatus());
            var second = executor.submit(() -> sendChat(secondUser, secondCase, UUID.randomUUID(), "질문")
                    .andReturn().getResponse().getStatus());
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                sendChat(thirdUser, thirdCase, UUID.randomUUID(), "질문").andExpect(status().isTooManyRequests());
                chatHistory(thirdUser, thirdCase).andExpect(jsonPath("$.data.items[0].retryAllowed").value(true));
            } finally { release.countDown(); }
            assertEquals(200, first.get(10, TimeUnit.SECONDS));
            assertEquals(200, second.get(10, TimeUnit.SECONDS));
        }
        org.mockito.Mockito.verify(openAiChat, org.mockito.Mockito.times(2)).generate(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void chatConfigurationFailureDoesNotBlockStoredRepliesOrSaveNewQuestion() throws Exception {
        var owner = registerTestUser();
        UUID caseId = createHttpCase(owner, tokenUserId(owner));
        UUID key = UUID.randomUUID();
        stubChat();
        sendChat(owner, caseId, key, "질문").andExpect(status().isOk());
        org.mockito.Mockito.doThrow(new BusinessException(ErrorCode.INTEGRATION_NOT_CONFIGURED))
                .when(openAiChat).requireConfigured();
        sendChat(owner, caseId, UUID.randomUUID(), "새 질문").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("COMMON_002"));
        sendChat(owner, caseId, key, "질문").andExpect(status().isOk());
        chatHistory(owner, caseId).andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
        org.mockito.Mockito.verify(openAiChat, org.mockito.Mockito.times(1)).generate(org.mockito.ArgumentMatchers.anyList());
    }

    private kr.co.legalai.chat.entity.GeneratedAnswer chatAnswer() {
        return kr.co.legalai.chat.entity.GeneratedAnswer.builder().text("언제 발생했나요?")
                .model("test-model").responseId("resp_test").inputTokens(12).outputTokens(7).build();
    }

    private void stubChat() {
        org.mockito.Mockito.when(openAiChat.generate(org.mockito.ArgumentMatchers.anyList())).thenReturn(chatAnswer());
    }

    private org.springframework.test.web.servlet.ResultActions sendChat(AuthTokenResponse user, UUID caseId,
            UUID key, String content) throws Exception {
        return mockMvc.perform(post("/api/v1/cases/{caseId}/chat/turns", caseId)
                .header("Authorization", "Bearer " + user.accessToken()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(
                        new kr.co.legalai.chat.dto.request.SendChatRequest(content))));
    }

    private org.springframework.test.web.servlet.ResultActions retryChat(AuthTokenResponse user, UUID caseId,
            UUID key, int attempt) throws Exception {
        return mockMvc.perform(post("/api/v1/cases/{caseId}/chat/turns/{id}/retry", caseId, key)
                .header("Authorization", "Bearer " + user.accessToken()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedAttempt\":" + attempt + "}"));
    }

    private org.springframework.test.web.servlet.ResultActions chatHistory(AuthTokenResponse user, UUID caseId) throws Exception {
        return mockMvc.perform(get("/api/v1/cases/{caseId}/chat/turns", caseId)
                .header("Authorization", "Bearer " + user.accessToken()));
    }

    @Test
    void lawClassificationAndChunkTopicsHaveIndependentDatabaseConstraints() throws Exception {
        try (var connection = adminConnection(); var statement = connection.createStatement()) {
            UUID source = UUID.randomUUID();
            UUID document = UUID.randomUUID();
            UUID chunk = UUID.randomUUID();
            statement.executeUpdate("""
                    INSERT INTO knowledge.legal_sources(id, source_code, source_type, publisher, authority_level)
                    VALUES ('%s', '%s', 'law', 'test', 1)
                    """.formatted(source, source));
            statement.executeUpdate("""
                    INSERT INTO knowledge.legal_documents(id, source_id, external_id, document_type, title,
                        version_label, source_url, raw_text, content_hash)
                    VALUES ('%s', '%s', 'test', 'law', '민법', 'v1', 'https://www.law.go.kr', 'test', '%s')
                    """.formatted(document, source, "a".repeat(64)));
            try (var rows = statement.executeQuery("SELECT law_kind FROM knowledge.legal_documents WHERE id='" + document + "'")) {
                rows.next();
                org.junit.jupiter.api.Assertions.assertNull(rows.getString(1));
            }
            statement.executeUpdate("UPDATE knowledge.legal_documents SET law_kind='ACT' WHERE id='" + document + "'");
            assertEquals("23514", assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE knowledge.legal_documents SET law_kind='민법' WHERE id='" + document + "'"
            )).getSQLState());
            assertEquals("23514", assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE knowledge.legal_documents SET document_type='precedent' WHERE id='" + document + "'"
            )).getSQLState());
            statement.executeUpdate("""
                    INSERT INTO knowledge.legal_chunks(id, document_id, chunk_type, content, ordinal)
                    VALUES ('%s', '%s', 'article', '수선의무', 0)
                    """.formatted(chunk, document));
            try (var update = connection.prepareStatement("UPDATE knowledge.legal_chunks SET metadata=?::jsonb WHERE id=?")) {
                update.setObject(2, chunk);
                for (String valid : List.of("{}", "{\"topic_tags\":[]}",
                        "{\"topic_tags\":[\"housing_lease\",\"repair_duty\"]}")) {
                    update.setString(1, valid);
                    assertEquals(1, update.executeUpdate());
                }
                for (String invalid : List.of("{\"topic_tags\":null}", "{\"topic_tags\":\"housing_lease\"}",
                        "{\"topic_tags\":[1]}", "{\"topic_tags\":[null]}", "{\"topic_tags\":[{}]}",
                        "{\"topic_tags\":[\"\"]}", "{\"topic_tags\":[\"   \"]}")) {
                    update.setString(1, invalid);
                    assertEquals("23514", assertThrows(SQLException.class, update::executeUpdate).getSQLState());
                }
            }
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
