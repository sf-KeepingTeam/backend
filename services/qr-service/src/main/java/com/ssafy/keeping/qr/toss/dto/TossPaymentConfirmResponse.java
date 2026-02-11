package com.ssafy.keeping.qr.toss.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TossPaymentConfirmResponse {

    private String paymentKey;
    private String orderId;
    private String orderName;
    private String status;
    private Long totalAmount;
    private Long balanceAmount;
    private String method;
    private OffsetDateTime requestedAt;
    private OffsetDateTime approvedAt;

    private CardInfo card;

    private String code;
    private String message;

    @Getter
    @Setter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CardInfo {
        private String company;
        private String number;
        private Integer installmentPlanMonths;
        private String approveNo;
        private Boolean useCardPoint;
        private String cardType;
        private String ownerType;
        private String acquireStatus;
        private String issuerCode;
        private String acquirerCode;
    }

    public boolean isSuccess() {
        return "DONE".equals(status);
    }
}
