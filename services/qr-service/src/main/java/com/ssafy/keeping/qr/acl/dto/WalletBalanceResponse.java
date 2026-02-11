package com.ssafy.keeping.qr.acl.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
public class WalletBalanceResponse {
    private Long walletId;
    private Long storeId;
    private BigDecimal balance;
}
