package com.ssafy.keeping.domain.wallet.dto;

import java.util.List;

public record PersonalWalletBalanceResponseDto(
        Long customerId,
        Long walletId,
        List<WalletStoreBalanceDetailDto> storeBalances
) {}