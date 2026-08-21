package com.ssafy.keeping.domain.charge.dto.response;

import java.time.LocalDateTime;
import lombok.*;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class PrepaymentResponseDto {

  private Long transactionId;
  private String transactionUniqueNo;
  private Long storeId;
  private String storeName;
  private Long paymentAmount;
  private Integer bonusPercentage;
  private Long bonusAmount;
  private Long totalPoints;
  private LocalDateTime transactionTime;
  private Long remainingBalance;
}
