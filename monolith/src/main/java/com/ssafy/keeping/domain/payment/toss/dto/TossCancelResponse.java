package com.ssafy.keeping.domain.payment.toss.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 토스페이먼츠 결제 취소 응답 DTO
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TossCancelResponse {

    private String paymentKey;          // 결제 키
    private String orderId;             // 주문 번호
    private String status;              // 결제 상태 (CANCELED, PARTIAL_CANCELED)
    private Long totalAmount;           // 총 결제 금액
    private Long balanceAmount;         // 남은 결제 금액

    // 취소 내역 목록
    private List<CancelInfo> cancels;

    // 에러 정보 (실패 시)
    private String code;
    private String message;

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CancelInfo {
        private Long cancelAmount;          // 취소 금액
        private String cancelReason;        // 취소 사유
        private Long taxFreeAmount;         // 면세 금액
        private Integer taxExemptionAmount; // 과세 제외 금액
        private Long refundableAmount;      // 환불 가능 금액
        private Long easyPayDiscountAmount; // 간편결제 할인 금액
        private OffsetDateTime canceledAt;  // 취소 시각
        private String transactionKey;      // 거래 키
        private String receiptKey;          // 취소 영수증 키
    }

    public boolean isSuccess() {
        return "CANCELED".equals(status) || "PARTIAL_CANCELED".equals(status);
    }

    /**
     * 가장 최근 취소 정보 반환
     */
    public CancelInfo getLatestCancel() {
        if (cancels == null || cancels.isEmpty()) {
            return null;
        }
        return cancels.get(cancels.size() - 1);
    }
}
