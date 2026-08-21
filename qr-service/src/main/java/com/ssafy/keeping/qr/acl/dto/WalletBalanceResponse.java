package com.ssafy.keeping.qr.acl.dto;

import java.math.BigDecimal;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class WalletBalanceResponse {
  private Long walletId;
  private Long storeId;
  private BigDecimal balance;
}
