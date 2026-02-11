package com.ssafy.keeping.qr.gateway.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class PaymentResult {
    private final boolean success;
    private final String paymentKey;
    private final String orderId;
    private final Long totalAmount;
    private final String method;
    private final String status;
    private final LocalDateTime approvedAt;

    private final String errorCode;
    private final String errorMessage;

    private final String cardCompany;
    private final String cardNumber;

    public static PaymentResult success(String paymentKey, String orderId, Long totalAmount,
                                        String method, LocalDateTime approvedAt) {
        return PaymentResult.builder()
                .success(true)
                .paymentKey(paymentKey)
                .orderId(orderId)
                .totalAmount(totalAmount)
                .method(method)
                .status("DONE")
                .approvedAt(approvedAt)
                .build();
    }

    public static PaymentResult failure(String errorCode, String errorMessage) {
        return PaymentResult.builder()
                .success(false)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }
}
