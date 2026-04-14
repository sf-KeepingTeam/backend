package com.ssafy.keeping.qr.acl.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentCheckResponse {
    private boolean exists;
    private Long transactionId;
    private Long amount;
    private Long walletId;
    private Long storeId;
}
