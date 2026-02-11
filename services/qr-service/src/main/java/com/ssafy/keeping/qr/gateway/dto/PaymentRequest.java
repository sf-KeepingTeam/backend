package com.ssafy.keeping.qr.gateway.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PaymentRequest {
    private final String paymentKey;
    private final String orderId;
    private final Long amount;
    private final Long storeId;
    private final Long customerId;
    private final String customerName;
    private final String customerEmail;
}
