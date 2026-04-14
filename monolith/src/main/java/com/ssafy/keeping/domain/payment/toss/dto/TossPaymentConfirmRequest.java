package com.ssafy.keeping.domain.payment.toss.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 토스페이먼츠 결제 승인 요청 DTO
 * POST /v1/payments/confirm
 */
@Getter
@Builder
public class TossPaymentConfirmRequest {

    private final String paymentKey;    // 토스에서 발급한 결제 키
    private final String orderId;       // 주문 번호
    private final Long amount;          // 결제 금액
}
