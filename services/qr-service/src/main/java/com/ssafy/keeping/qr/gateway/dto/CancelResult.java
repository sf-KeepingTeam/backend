package com.ssafy.keeping.qr.gateway.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class CancelResult {
    private final boolean success;
    private final String paymentKey;
    private final Long cancelAmount;
    private final String cancelReason;
    private final LocalDateTime canceledAt;
    private final String status;

    private final String errorCode;
    private final String errorMessage;

    public static CancelResult success(String paymentKey, Long cancelAmount,
                                       String cancelReason, LocalDateTime canceledAt) {
        return CancelResult.builder()
                .success(true)
                .paymentKey(paymentKey)
                .cancelAmount(cancelAmount)
                .cancelReason(cancelReason)
                .canceledAt(canceledAt)
                .status("CANCELED")
                .build();
    }

    public static CancelResult failure(String errorCode, String errorMessage) {
        return CancelResult.builder()
                .success(false)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }
}
