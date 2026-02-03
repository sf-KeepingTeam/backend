package com.ssafy.keeping.qrpayment.acl.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class WalletBalanceResponse {
    private Long walletId;
    private BigDecimal balance;
}
