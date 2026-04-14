package com.ssafy.keeping.domain.payment.gateway.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 공통 취소 요청 DTO
 */
@Getter
@Builder
public class CancelRequest {

    private final String paymentKey;        // 결제 키
    private final String cancelReason;      // 취소 사유
    private final Long cancelAmount;        // 취소 금액 (부분취소 시, null이면 전액취소)

    // 환불 계좌 정보 (가상계좌 결제 취소 시 필요)
    private final String refundBankCode;
    private final String refundAccountNumber;
    private final String refundHolderName;
}
