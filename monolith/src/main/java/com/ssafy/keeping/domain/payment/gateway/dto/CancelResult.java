package com.ssafy.keeping.domain.payment.gateway.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 공통 취소 결과 DTO
 */
@Getter
@Builder
public class CancelResult {

    private final boolean success;              // 취소 성공 여부
    private final String paymentKey;            // 결제 키
    private final Long cancelAmount;            // 취소 금액
    private final String cancelReason;          // 취소 사유
    private final LocalDateTime canceledAt;     // 취소 시각
    private final String status;                // 결제 상태 (CANCELED, PARTIAL_CANCELED 등)

    // 에러 정보 (실패 시)
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
