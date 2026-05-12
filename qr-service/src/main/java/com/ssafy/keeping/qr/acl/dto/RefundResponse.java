package com.ssafy.keeping.qr.acl.dto;

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
    private boolean permanent;
    private Long refundTransactionId;
    private String message;

    public static RefundResponse permanentFailed(int httpStatus, String body) {
        return RefundResponse.builder()
                .success(false)
                .permanent(true)
                .message("HTTP " + httpStatus + ": " + body)
                .build();
    }
}
