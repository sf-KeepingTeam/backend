package com.ssafy.keeping.domain.payment.toss.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 토스페이먼츠 결제 승인 응답 DTO
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TossPaymentConfirmResponse {

    private String paymentKey;          // 결제 키
    private String orderId;             // 주문 번호
    private String orderName;           // 주문명
    private String status;              // 결제 상태 (DONE, CANCELED 등)
    private Long totalAmount;           // 총 결제 금액
    private Long balanceAmount;         // 취소 가능 잔액
    private String method;              // 결제 수단 (카드, 가상계좌 등)
    private OffsetDateTime requestedAt; // 결제 요청 시각
    private OffsetDateTime approvedAt;  // 결제 승인 시각

    // 카드 결제 정보
    private CardInfo card;

    // 에러 정보 (실패 시)
    private String code;
    private String message;

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CardInfo {
        private String company;         // 카드사
        private String number;          // 마스킹된 카드번호
        private Integer installmentPlanMonths;  // 할부 개월
        private String approveNo;       // 승인번호
        private Boolean useCardPoint;   // 카드 포인트 사용 여부
        private String cardType;        // 카드 타입 (신용, 체크 등)
        private String ownerType;       // 소유자 타입 (개인, 법인)
        private String acquireStatus;   // 매입 상태
        private String issuerCode;      // 발급사 코드
        private String acquirerCode;    // 매입사 코드
    }

    public boolean isSuccess() {
        return "DONE".equals(status);
    }
}
