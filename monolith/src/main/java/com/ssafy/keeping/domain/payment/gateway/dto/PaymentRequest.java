package com.ssafy.keeping.domain.payment.gateway.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 공통 결제 요청 DTO
 * 모든 결제 제공자에서 사용하는 공통 필드
 */
@Getter
@Builder
public class PaymentRequest {

    private final String paymentKey;    // 결제 키 (토스에서 발급)
    private final String orderId;       // 주문 번호
    private final Long amount;          // 결제 금액
    private final Long storeId;         // 가게 ID
    private final Long customerId;      // 고객 ID

    // 추가 메타데이터 (필요시)
    private final String customerName;
    private final String customerEmail;
}
