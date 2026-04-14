package com.ssafy.keeping.domain.store.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PeriodStatisticsResponseDto {

    private Long storeId;
    private String storeName;
    private LocalDate startDate;
    private LocalDate endDate;

    // 기간 내 실제 결제금액 (보너스 제외)
    private Long periodPaymentAmount;

    // 기간 내 총 충전 포인트 금액 (보너스 포함)
    private Long periodTotalChargePoints;

    // 기간 내 포인트 사용량
    private Long periodPointsUsed;

    // 기간 내 거래 건수
    private Long periodTransactionCount;

    // 기간 내 충전 건수
    private Long periodChargeCount;

    // 기간 내 사용 건수
    private Long periodUseCount;

    // 일평균 결제금액
    private Long averageDailyPayment;

    // 일평균 포인트 사용량
    private Long averageDailyPointsUsed;
}