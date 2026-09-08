package kr.co.legalai.common.transaction;

import kr.co.legalai.common.security.AuthenticatedUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * 같은 DB 트랜잭션에 인증 사용자 ID를 local GUC로 설정해 PostgreSQL RLS를 강제한다.
 */
@Component
public class UserScopedTransaction {
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final AuthenticatedUser authenticatedUser;

    public UserScopedTransaction(
            TransactionTemplate transactionTemplate,
            JdbcTemplate jdbcTemplate,
            AuthenticatedUser authenticatedUser
    ) {
        this.transactionTemplate = transactionTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.authenticatedUser = authenticatedUser;
    }

    public <T> T execute(Function<UUID, T> action) {
        return Objects.requireNonNull(transactionTemplate.execute(status -> {
            UUID userId = authenticatedUser.getUserId();
            jdbcTemplate.queryForObject(
                    "select set_config('app.user_id', ?, true)",
                    String.class,
                    userId.toString()
            );
            return action.apply(userId);
        }), "User-scoped transaction returned null");
    }
}
