package com.ssafy.keeping.domain.payment.toss.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

/**
 * 토스페이먼츠 결제 취소 요청 DTO
 * POST /v1/payments/{paymentKey}/cancel
 */
@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TossCancelRequest {

    private final String cancelReason;      // 취소 사유 (필수)
    private final Long cancelAmount;        // 취소 금액 (부분취소 시)

    // 가상계좌 결제 취소 시 환불 계좌 정보
    private final RefundReceiveAccount refundReceiveAccount;

    @Getter
    @Builder
    public static class RefundReceiveAccount {
        private final String bank;          // 은행 코드
        private final String accountNumber; // 계좌번호
        private final String holderName;    // 예금주
    }
}
