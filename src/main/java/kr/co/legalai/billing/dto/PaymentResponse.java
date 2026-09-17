package kr.co.legalai.billing.dto;

public record PaymentResponse(String orderId, String status, String planCode) { }
