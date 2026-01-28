package com.ssafy.keeping.domain.charge.dto.response;

import lombok.Builder;

/**
 * 취소 준비 결과 DTO
 * Phase 1 (트랜잭션): 로컬 DB 변경 후 외부 API 호출에 필요한 정보 반환
 */
@Builder
public record CancelPrepareResult(
        Long transactionId,
        String paymentKey,
        Long customerId,
        Long storeId,
        Long walletId,
        Long lotId,
        Long cancelAmount,
        Long bonusAmount,
        String cancelReason
) {
}
