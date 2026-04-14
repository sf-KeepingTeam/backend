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
public class DailyStatisticsResponseDto {

    private Long storeId;
    private String storeName;
    private LocalDate date;

    // 해당 날짜 실제 결제금액 (보너스 제외)
    private Long dailyPaymentAmount;

    // 해당 날짜 총 충전 포인트 금액 (보너스 포함)
    private Long dailyTotalChargePoints;

    // 해당 날짜 포인트 사용량
    private Long dailyPointsUsed;

    // 해당 날짜 거래 건수
    private Long dailyTransactionCount;

    // 해당 날짜 충전 건수
    private Long dailyChargeCount;

    // 해당 날짜 사용 건수
    private Long dailyUseCount;
}