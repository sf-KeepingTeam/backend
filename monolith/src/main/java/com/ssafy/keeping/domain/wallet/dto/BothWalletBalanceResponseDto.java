package com.ssafy.keeping.domain.wallet.dto;

import java.util.List;

/**
 * 개인 지갑 + 모임 지갑들의 통합 조회 응답 DTO
 */
public record BothWalletBalanceResponseDto(
        PersonalWalletBalanceResponseDto personalWallet,
        List<GroupWalletBalanceResponseDto> groupWallets
) {
}