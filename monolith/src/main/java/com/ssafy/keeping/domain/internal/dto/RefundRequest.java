package com.ssafy.keeping.domain.internal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundRequest {
    private Long walletId;
    private Long storeId;
    private Long amount;
    private Long originalTransactionId;
    private String reason;
}
