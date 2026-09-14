package kr.co.legalai.billing.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConfirmPaymentRequest(@NotBlank @Size(max = 200) String paymentKey,
                                    @NotBlank @Size(min = 6, max = 64) String orderId,
                                    @Min(1) long amount) { }
