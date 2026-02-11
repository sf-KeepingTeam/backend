package com.ssafy.keeping.qr.gateway.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class CancelRequest {
    private final String paymentKey;
    private final String cancelReason;
    private final Long cancelAmount;
    private final String refundBankCode;
    private final String refundAccountNumber;
    private final String refundHolderName;
}
