package com.ssafy.keeping.domain.wallet.dto;

import java.time.LocalDateTime;

public record WalletStoreBalanceDetailDto(
        Long storeId,
        String storeName,
        Long remainingPoints,
        LocalDateTime lastUpdatedAt
) {
}