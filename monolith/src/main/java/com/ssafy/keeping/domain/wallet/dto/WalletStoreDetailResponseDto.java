package com.ssafy.keeping.domain.wallet.dto;

import org.springframework.data.domain.Page;

public record WalletStoreDetailResponseDto(
        Long storeId,
        String storeName,
        Long currentBalance,                           // 현재 포인트 잔액
        Page<WalletStoreTransactionDetailDto> transactions  // 거래내역 (페이징)
) {
}