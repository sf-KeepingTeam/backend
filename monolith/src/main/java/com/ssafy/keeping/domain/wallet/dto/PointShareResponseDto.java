package com.ssafy.keeping.domain.wallet.dto;

import java.time.LocalDateTime;

public record PointShareResponseDto(
        Long txOutId,                 // 개인→모임 이체 OUT transaction id
        Long txInId,                  // 모임 지갑 수신 IN  transaction id
        Long individualWalletId,
        Long groupWalletId,
        Long storeId,
        Long amount,
        Long newGroupBalance,   // 모임 지갑 해당 매장 잔액
        Long newIndividualBalance, // 개인 지갑 해당 매장 잔액(차감 후)
        LocalDateTime occurredAt,
        boolean idempotentReplayed    // 멱등키 재실행 여부
) {}