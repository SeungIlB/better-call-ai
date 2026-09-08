package kr.co.legalai.common.persistence

import kr.co.legalai.common.security.AuthenticatedUser
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@Configuration
class TransactionConfiguration {
    @Bean
    fun transactionTemplate(transactionManager: PlatformTransactionManager) =
        TransactionTemplate(transactionManager)
}

@Component
class UserScopedTransaction(
    private val transactionTemplate: TransactionTemplate,
    private val jdbcTemplate: JdbcTemplate,
    private val authenticatedUser: AuthenticatedUser,
) {
    fun <T> execute(block: (UUID) -> T): T =
        transactionTemplate.execute {
            val userId = authenticatedUser.id()
            jdbcTemplate.queryForObject(
                "select set_config('app.user_id', ?, true)",
                String::class.java,
                userId.toString(),
            )
            block(userId)
        } ?: error("User-scoped transaction returned null")
}
