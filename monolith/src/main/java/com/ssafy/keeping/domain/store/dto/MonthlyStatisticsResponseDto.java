package com.ssafy.keeping.domain.store.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonthlyStatisticsResponseDto {

    private Long storeId;
    private String storeName;
    private int year;
    private int month;

    // 해당 월 실제 결제금액 (보너스 제외)
    private Long monthlyPaymentAmount;

    // 해당 월 총 충전 포인트 금액 (보너스 포함)
    private Long monthlyTotalChargePoints;

    // 해당 월 포인트 사용량
    private Long monthlyPointsUsed;

    // 해당 월 거래 건수
    private Long monthlyTransactionCount;

    // 해당 월 충전 건수
    private Long monthlyChargeCount;

    // 해당 월 사용 건수
    private Long monthlyUseCount;

    // 일평균 결제금액
    private Long averageDailyPayment;

    // 일평균 포인트 사용량
    private Long averageDailyPointsUsed;
}