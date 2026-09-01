package com.ssafy.keeping.domain.charge.dto.response;

import java.time.LocalDateTime;
import lombok.*;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class CancelResponseDto {

  private Long cancelTransactionId;
  private String transactionUniqueNo;
  private Long cancelAmount;
  private LocalDateTime cancelTime;
  private Long remainingBalance;
}
