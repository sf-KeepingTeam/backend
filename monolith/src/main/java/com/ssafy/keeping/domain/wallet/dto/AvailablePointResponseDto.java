package com.ssafy.keeping.domain.wallet.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AvailablePointResponseDto {
    private Long storeId;
    private Long walletId;
    private Long memberId;
    private Long available;
}
