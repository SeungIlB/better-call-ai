package kr.co.legalai.billing.service;
import kr.co.legalai.billing.dto.*;
public interface PaymentService { PaymentResponse createOrder(); PaymentResponse confirm(ConfirmPaymentRequest request); }
