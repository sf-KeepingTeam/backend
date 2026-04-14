package com.ssafy.keeping.domain.internal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundResponse {
    private boolean success;
    private Long refundTransactionId;
    private String message;

    public static RefundResponse ok(Long refundTransactionId) {
        return RefundResponse.builder()
                .success(true)
                .refundTransactionId(refundTransactionId)
                .message("환불 완료")
                .build();
    }

    public static RefundResponse failed(String message) {
        return RefundResponse.builder()
                .success(false)
                .message(message)
                .build();
    }
}
