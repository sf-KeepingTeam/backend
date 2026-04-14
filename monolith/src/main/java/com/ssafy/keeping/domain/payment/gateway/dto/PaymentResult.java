package com.ssafy.keeping.domain.payment.gateway.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 공통 결제 결과 DTO
 * 모든 결제 제공자의 결과를 통일된 형식으로 반환
 */
@Getter
@Builder
public class PaymentResult {

    private final boolean success;              // 결제 성공 여부
    private final String paymentKey;            // 결제 키
    private final String orderId;               // 주문 번호
    private final Long totalAmount;             // 총 결제 금액
    private final String method;                // 결제 수단 (카드, 계좌이체 등)
    private final String status;                // 결제 상태 (DONE, CANCELED 등)
    private final LocalDateTime approvedAt;     // 승인 시각

    // 에러 정보 (실패 시)
    private final String errorCode;
    private final String errorMessage;

    // 카드 정보 (카드 결제 시)
    private final String cardCompany;           // 카드사
    private final String cardNumber;            // 마스킹된 카드번호

    public static PaymentResult success(String paymentKey, String orderId, Long totalAmount,
                                        String method, LocalDateTime approvedAt) {
        return PaymentResult.builder()
                .success(true)
                .paymentKey(paymentKey)
                .orderId(orderId)
                .totalAmount(totalAmount)
                .method(method)
                .status("DONE")
                .approvedAt(approvedAt)
                .build();
    }

    public static PaymentResult failure(String errorCode, String errorMessage) {
        return PaymentResult.builder()
                .success(false)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }
}
