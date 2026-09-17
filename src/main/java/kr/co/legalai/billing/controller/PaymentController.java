package kr.co.legalai.billing.controller;
import jakarta.validation.Valid;
import kr.co.legalai.billing.dto.*;
import kr.co.legalai.billing.service.PaymentService;
import kr.co.legalai.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor @RequestMapping("/api/v1/billing")
public class PaymentController {
    private final PaymentService service;
    @PostMapping("/orders")
    public ApiResponse<PaymentResponse> createOrder() { return ApiResponse.success(service.createOrder()); }
    @PostMapping("/payments/confirm")
    public ApiResponse<PaymentResponse> confirm(@Valid @RequestBody ConfirmPaymentRequest request) { return ApiResponse.success(service.confirm(request)); }
}
