package kr.co.legalai

import kr.co.legalai.casework.api.CreateCaseRequest
import kr.co.legalai.casework.api.UpdateStatementRequest
import kr.co.legalai.casework.application.CaseService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.server.ResponseStatusException
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID

@SpringBootTest
class CaseFlowIntegrationTest {
    @Autowired
    private lateinit var service: CaseService

    private val userA = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val userB = UUID.fromString("22222222-2222-2222-2222-222222222222")

    @BeforeEach
    fun createUsers() {
        adminConnection().use { connection ->
            connection.createStatement().use {
                it.executeUpdate(
                    """
                    INSERT INTO identity.users(id, status)
                    VALUES ('$userA', 'active'), ('$userB', 'active')
                    ON CONFLICT (id) DO NOTHING
                    """.trimIndent(),
                )
            }
        }
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `case write is versioned and isolated by RLS`() {
        authenticate(userA)
        val created = service.create(
            CreateCaseRequest(
                title = "중고거래 환불 분쟁",
                userPartyRole = "구매자",
                originalStatement = "물건이 설명과 달랐습니다.",
            ),
        )

        val updated = service.updateStatement(
            created.id,
            UpdateStatementRequest(
                statement = "판매자가 하자를 고지하지 않았습니다.",
                expectedVersion = 1,
            ),
        )

        assertEquals(2, updated.version)
        assertEquals("COLLECTING", updated.status)
        assertEquals(2, outboxCount(created.id))

        authenticate(userB)
        assertThrows(ResponseStatusException::class.java) {
            service.get(created.id)
        }
    }

    private fun authenticate(userId: UUID) {
        SecurityContextHolder.getContext().authentication =
            TestingAuthenticationToken(userId.toString(), null)
    }

    private fun outboxCount(caseId: UUID): Int =
        adminConnection().use { connection ->
            connection.prepareStatement(
                "SELECT count(*) FROM ops.outbox_events WHERE aggregate_id = ?",
            ).use {
                it.setObject(1, caseId)
                it.executeQuery().use { result ->
                    result.next()
                    result.getInt(1)
                }
            }
        }

    private fun adminConnection() =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    companion object {
        @JvmStatic
        val postgres = PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16")
                .asCompatibleSubstituteFor("postgres"),
        ).also { container ->
            container.start()
            val result = container.execInContainer(
                "psql",
                "-U",
                container.username,
                "-d",
                container.databaseName,
                "-v",
                "ON_ERROR_STOP=1",
                "-c",
                """
                CREATE ROLE legal_ai_app LOGIN PASSWORD 'app_test' NOBYPASSRLS;
                CREATE ROLE legal_ai_auth LOGIN PASSWORD 'auth_test' BYPASSRLS;
                """.trimIndent(),
            )
            check(result.exitCode == 0) { result.stderr }
        }

        @JvmStatic
        @DynamicPropertySource
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username") { "legal_ai_app" }
            registry.add("spring.datasource.password") { "app_test" }
            registry.add("spring.flyway.url", postgres::getJdbcUrl)
            registry.add("spring.flyway.user", postgres::getUsername)
            registry.add("spring.flyway.password", postgres::getPassword)
            registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri") { "http://issuer.test" }
            registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri") { "http://issuer.test/jwks" }
        }
    }
}
