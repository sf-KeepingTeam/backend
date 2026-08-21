package com.ssafy.keeping.domain.charge.dto.response;

import java.time.LocalDateTime;
import lombok.*;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class CancelListResponseDto {

  private String transactionUniqueNo;
  private String storeName;
  private Long paymentAmount;
  private LocalDateTime transactionTime;
  private Long remainingBalance;
}
