package com.ssafy.keeping.domain.payment.refund.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RefundResponse {
    private Long transactionId;
    private Long refundTransactionId;
    private Long amount;
    private LocalDateTime refundedAt;
}