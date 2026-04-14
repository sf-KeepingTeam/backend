package com.ssafy.keeping.domain.wallet.dto;


import java.util.List;

public record GroupWalletBalanceResponseDto(
        Long groupId,
        Long walletId,
        String groupName,
        List<WalletStoreBalanceDetailDto> storeBalances
) {}