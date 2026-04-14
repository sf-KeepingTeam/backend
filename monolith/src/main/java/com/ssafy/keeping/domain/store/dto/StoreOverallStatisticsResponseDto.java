package com.ssafy.keeping.domain.store.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoreOverallStatisticsResponseDto {

    private Long storeId;

    private String storeName;

    private Long totalPaymentAmount; // 전체 누적 실제 결제금액 (보너스 제외)

    private Long totalChargePoints; // 전체 누적 총 충전 포인트 금액 (보너스 포함)

    private Long totalPointsUsed; // 전체 누적 포인트 사용량

    private Long totalTransactionCount; // 전체 거래 건수

    private Long totalChargeCount; // 전체 충전 건수

    private Long totalUseCount; // 전체 사용 건수
}