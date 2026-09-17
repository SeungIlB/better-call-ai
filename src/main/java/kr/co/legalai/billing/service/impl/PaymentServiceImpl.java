package kr.co.legalai.billing.service.impl;

import kr.co.legalai.billing.dto.*;
import kr.co.legalai.billing.repository.*;
import kr.co.legalai.billing.service.PaymentService;
import kr.co.legalai.common.exception.*;
import kr.co.legalai.common.security.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;

@Service @RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {
    private final PaymentRepository repository; private final TossPaymentRepository toss; private final AuthenticatedUser user;
    private final TransactionTemplate transaction;
    public PaymentResponse createOrder() {
        String orderId = "BCAI-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        repository.createOrder(user.getUserId(), orderId, 9900, Instant.now().plus(10, java.time.temporal.ChronoUnit.MINUTES));
        return new PaymentResponse(orderId, "READY", "PAID");
    }
    public PaymentResponse confirm(ConfirmPaymentRequest request) {
        var order = repository.findOrder(request.orderId(), user.getUserId()).orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_ORDER_NOT_FOUND));
        if (!"READY".equals(order.status())) throw new BusinessException(ErrorCode.PAYMENT_ALREADY_PROCESSED);
        if (order.amount() != request.amount()) throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        try { toss.confirm(request.paymentKey(), request.orderId(), request.amount()); }
        catch (IllegalStateException e) { if ("PAYMENT_SECRET_MISSING".equals(e.getMessage())) throw new BusinessException(ErrorCode.INTEGRATION_NOT_CONFIGURED); throw new BusinessException(ErrorCode.PAYMENT_FAILED); }
        transaction.executeWithoutResult(s -> { repository.confirm(order.orderId(), request.paymentKey(), Instant.now()); repository.grantPaid(order.userId(), Instant.now().plus(30, java.time.temporal.ChronoUnit.DAYS)); });
        return new PaymentResponse(order.orderId(), "CONFIRMED", "PAID");
    }
}
