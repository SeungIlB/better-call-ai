package kr.co.legalai.billing.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class PaymentRepository {
    private final JdbcTemplate jdbc;
    public void createOrder(UUID userId, String orderId, long amount, Instant expiresAt) {
        jdbc.update("INSERT INTO billing.payment_orders(user_id,order_id,plan_code,amount,expires_at) VALUES (?,?,'PAID',?,?)", userId, orderId, amount, java.sql.Timestamp.from(expiresAt));
    }
    public Optional<Order> findOrder(String orderId, UUID userId) {
        return jdbc.query("SELECT order_id,user_id,plan_code,amount,status,expires_at FROM billing.payment_orders WHERE order_id=? AND user_id=?",
                (rs, n) -> new Order(rs.getString("order_id"), rs.getObject("user_id", UUID.class), rs.getString("plan_code"), rs.getLong("amount"), rs.getString("status"), rs.getTimestamp("expires_at").toInstant()), orderId, userId).stream().findFirst();
    }
    public void confirm(String orderId, String paymentKey, Instant now) {
        jdbc.update("UPDATE billing.payment_orders SET payment_key=?,status='CONFIRMED',confirmed_at=?,updated_at=? WHERE order_id=? AND status='READY'",
                paymentKey, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), orderId);
    }
    public void grantPaid(UUID userId, Instant expiresAt) {
        jdbc.update("INSERT INTO identity.user_plans(user_id,plan_code,expires_at) VALUES (?, 'PAID', ?) ON CONFLICT (user_id) DO UPDATE SET plan_code='PAID', expires_at=GREATEST(identity.user_plans.expires_at, EXCLUDED.expires_at)", userId, java.sql.Timestamp.from(expiresAt));
    }
    public record Order(String orderId, UUID userId, String planCode, long amount, String status, Instant expiresAt) { }
}
