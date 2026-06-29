package com.ssafy.keeping.domain.charge.dto;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
@Builder
public class CancelReclaimResult {

    private final Long cancelTransactionId;
    private final String transactionUniqueNo;
    private final Long cancelAmount;
    private final LocalDateTime cancelTime;
    private final Long remainingBalance;
    private final String paymentKey;
}
