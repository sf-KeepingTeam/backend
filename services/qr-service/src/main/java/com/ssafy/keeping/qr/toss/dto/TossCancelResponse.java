package com.ssafy.keeping.qr.toss.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TossCancelResponse {

    private String paymentKey;
    private String orderId;
    private String status;
    private Long totalAmount;
    private Long balanceAmount;

    private List<CancelInfo> cancels;

    private String code;
    private String message;

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CancelInfo {
        private Long cancelAmount;
        private String cancelReason;
        private Long taxFreeAmount;
        private Integer taxExemptionAmount;
        private Long refundableAmount;
        private Long easyPayDiscountAmount;
        private OffsetDateTime canceledAt;
        private String transactionKey;
        private String receiptKey;
    }

    public boolean isSuccess() {
        return "CANCELED".equals(status) || "PARTIAL_CANCELED".equals(status);
    }

    public CancelInfo getLatestCancel() {
        if (cancels == null || cancels.isEmpty()) {
            return null;
        }
        return cancels.get(cancels.size() - 1);
    }
}
