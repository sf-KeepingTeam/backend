package com.ssafy.keeping.domain.charge.dto;

import java.time.LocalDateTime;
import lombok.*;

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
