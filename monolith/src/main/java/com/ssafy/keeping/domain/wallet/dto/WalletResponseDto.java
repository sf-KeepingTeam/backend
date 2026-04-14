package com.ssafy.keeping.domain.wallet.dto;

import com.ssafy.keeping.domain.wallet.constant.WalletType;
import com.ssafy.keeping.domain.wallet.model.Wallet;

import java.time.LocalDateTime;
import java.util.List;

public record WalletResponseDto(
        Long walletId,
        WalletType WalletType,
        Long connectId, // customerId or groupId
        List<WalletStoreBalanceResponseDto> storeBalances,
        LocalDateTime createdAt
) {}
