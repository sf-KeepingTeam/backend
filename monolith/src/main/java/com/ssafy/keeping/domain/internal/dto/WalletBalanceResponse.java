package com.ssafy.keeping.domain.internal.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletBalanceResponse {
  private Long walletId;
  private Long storeId;
  private BigDecimal balance;
}
