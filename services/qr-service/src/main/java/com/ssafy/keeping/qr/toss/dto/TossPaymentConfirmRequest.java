package com.ssafy.keeping.qr.toss.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TossPaymentConfirmRequest {
    private final String paymentKey;
    private final String orderId;
    private final Long amount;
}
