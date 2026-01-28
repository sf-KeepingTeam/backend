package com.ssafy.keeping.domain.charge.dto.response;

import lombok.Builder;

/**
 * 결제 승인 준비 결과 DTO
 * Phase 1 (트랜잭션): 검증 완료 후 외부 API 호출에 필요한 정보 반환
 */
@Builder
public record PrepaymentPrepareResult(
        Long reservationId,
        String orderId,
        String paymentKey,
        Long customerId,
        Long storeId,
        String storeName,
        Long walletId,
        Long amount
) {
}
