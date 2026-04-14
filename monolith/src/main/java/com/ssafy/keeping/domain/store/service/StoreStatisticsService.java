package com.ssafy.keeping.domain.store.service;

import com.ssafy.keeping.domain.payment.transactions.repository.TransactionRepository;
import com.ssafy.keeping.domain.store.dto.DailyStatisticsResponseDto;
import com.ssafy.keeping.domain.store.dto.MonthlyStatisticsResponseDto;
import com.ssafy.keeping.domain.store.dto.PeriodStatisticsResponseDto;
import com.ssafy.keeping.domain.store.dto.StatisticsRequestDto;
import com.ssafy.keeping.domain.store.dto.StoreOverallStatisticsResponseDto;
import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.store.repository.StoreRepository;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class StoreStatisticsService {

    private final StoreRepository storeRepository;
    private final TransactionRepository transactionRepository;

    /**
     * 가게 전체 누적 통계 조회
     */
    public StoreOverallStatisticsResponseDto getOverallStatistics(Long storeId, Long ownerId) {
        log.info("전체 통계 조회 시작 - 가게ID: {}, 점주ID: {}", storeId, ownerId);

        // 1. 가게 존재 및 권한 검증
        Store store = validateStoreOwnership(storeId, ownerId);

        // 2. 통계 데이터 조회 // todo: 여러번 DB를 조회하지 않고 한번에 가져와서 처리하는 방법이 있지 않을까..????!!!!!
        Long totalPaymentAmount = transactionRepository.getTotalPaymentAmountByStore(storeId); // 총 결제 금액 - 취소는 조회 안하게끔함
        Long totalChargePoints = transactionRepository.getTotalChargePointsByStore(storeId); // 총 충전 포인트 (보너스 포함)
        Long totalPointsUsed = transactionRepository.getTotalPointsUsedByStore(storeId); // point 총 사용량
        Long totalTransactionCount = transactionRepository.getTotalTransactionCountByStore(storeId); // 총 포인트 거래 수 (충전, 취소, 사용, 회수 포함)
        Long totalChargeCount = transactionRepository.getTotalChargeCountByStore(storeId); // 총 충전 수
        Long totalUseCount = transactionRepository.getTotalUseCountByStore(storeId); // 총 포인트 사용 수

        log.info("전체 통계 조회 완료 - 가게: {}, 총결제: {}원, 총사용: {}포인트",
                store.getStoreName(), totalPaymentAmount, totalPointsUsed);

        return StoreOverallStatisticsResponseDto.builder()
                .storeId(store.getStoreId())
                .storeName(store.getStoreName())
                .totalPaymentAmount(totalPaymentAmount)
                .totalChargePoints(totalChargePoints)
                .totalPointsUsed(totalPointsUsed)
                .totalTransactionCount(totalTransactionCount)
                .totalChargeCount(totalChargeCount)
                .totalUseCount(totalUseCount)
                .build();
    }

    /**
     * 가게 일별 통계 조회
     */
    public DailyStatisticsResponseDto getDailyStatistics(Long storeId, Long ownerId, StatisticsRequestDto requestDto) {
        log.info("일별 통계 조회 시작 - 가게ID: {}, 점주ID: {}, 날짜: {}",
                storeId, ownerId, requestDto.getDate());

        // 1. 가게 존재 및 권한 검증
        Store store = validateStoreOwnership(storeId, ownerId);

        // 2. 날짜 검증
        LocalDate date = requestDto.getDate();
        if (date == null) {
            date = LocalDate.now(); // 기본값: 오늘
        }

        // 3. 통계 데이터 조회
        Long dailyPaymentAmount = transactionRepository.getDailyPaymentAmountByStore(storeId, date);
        Long dailyTotalChargePoints = transactionRepository.getDailyTotalChargePointsByStore(storeId, date);
        Long dailyPointsUsed = transactionRepository.getDailyPointsUsedByStore(storeId, date);
        Long dailyChargeCount = transactionRepository.getDailyChargeCountByStore(storeId, date);
        Long dailyUseCount = transactionRepository.getDailyUseCountByStore(storeId, date);
        Long dailyTransactionCount = transactionRepository.getDailyTransactionCountByStore(storeId, date); // 모든 거래 타입 포함

        log.info("일별 통계 조회 완료 - 가게: {}, 날짜: {}, 결제: {}원, 사용: {}포인트",
                store.getStoreName(), date, dailyPaymentAmount, dailyPointsUsed);

        return DailyStatisticsResponseDto.builder()
                .storeId(store.getStoreId())
                .storeName(store.getStoreName())
                .date(date)
                .dailyPaymentAmount(dailyPaymentAmount)
                .dailyTotalChargePoints(dailyTotalChargePoints)
                .dailyPointsUsed(dailyPointsUsed)
                .dailyTransactionCount(dailyTransactionCount)
                .dailyChargeCount(dailyChargeCount)
                .dailyUseCount(dailyUseCount)
                .build();
    }

    /**
     * 가게 기간별 통계 조회
     */
    public PeriodStatisticsResponseDto getPeriodStatistics(Long storeId, Long ownerId, StatisticsRequestDto requestDto) {
        log.info("기간별 통계 조회 시작 - 가게ID: {}, 점주ID: {}, 기간: {} ~ {}",
                storeId, ownerId, requestDto.getStartDate(), requestDto.getEndDate());

        // 1. 가게 존재 및 권한 검증
        Store store = validateStoreOwnership(storeId, ownerId);

        // 2. 날짜 검증
        LocalDate startDate = requestDto.getStartDate();
        LocalDate endDate = requestDto.getEndDate();

        if (startDate == null || endDate == null) {
            throw new CustomException(ErrorCode.INVALID_DATE_RANGE); // 사용 꼭 받아야함
        }

        if (startDate.isAfter(endDate)) {
            throw new CustomException(ErrorCode.INVALID_DATE_RANGE);
        }

        // 3. 통계 데이터 조회
        Long periodPaymentAmount = transactionRepository.getPeriodPaymentAmountByStore(storeId, startDate, endDate);
        Long periodTotalChargePoints = transactionRepository.getPeriodTotalChargePointsByStore(storeId, startDate, endDate);
        Long periodPointsUsed = transactionRepository.getPeriodPointsUsedByStore(storeId, startDate, endDate);
        Long periodChargeCount = transactionRepository.getPeriodChargeCountByStore(storeId, startDate, endDate);
        Long periodUseCount = transactionRepository.getPeriodUseCountByStore(storeId, startDate, endDate);
        Long periodTransactionCount = transactionRepository.getPeriodTransactionCountByStore(storeId, startDate, endDate); // 모든 거래 타입 포함

        // 4. 일평균 계산
        long daysBetween = ChronoUnit.DAYS.between(startDate, endDate) + 1; // 시작일과 종료일 포함
        Long averageDailyPayment = daysBetween > 0 ? periodPaymentAmount / daysBetween : 0L;
        Long averageDailyPointsUsed = daysBetween > 0 ? periodPointsUsed / daysBetween : 0L;

        log.info("기간별 통계 조회 완료 - 가게: {}, 기간: {}일, 결제: {}원, 사용: {}포인트",
                store.getStoreName(), daysBetween, periodPaymentAmount, periodPointsUsed);

        return PeriodStatisticsResponseDto.builder()
                .storeId(store.getStoreId())
                .storeName(store.getStoreName())
                .startDate(startDate)
                .endDate(endDate)
                .periodPaymentAmount(periodPaymentAmount)
                .periodTotalChargePoints(periodTotalChargePoints)
                .periodPointsUsed(periodPointsUsed)
                .periodTransactionCount(periodTransactionCount)
                .periodChargeCount(periodChargeCount)
                .periodUseCount(periodUseCount)
                .averageDailyPayment(averageDailyPayment)
                .averageDailyPointsUsed(averageDailyPointsUsed)
                .build();
    }

    /**
     * 가게 월별 통계 조회
     */
    public MonthlyStatisticsResponseDto getMonthlyStatistics(Long storeId, Long ownerId, StatisticsRequestDto requestDto) {
        log.info("월별 통계 조회 시작 - 가게ID: {}, 점주ID: {}", storeId, ownerId);

        // 1. 가게 존재 및 권한 검증
        Store store = validateStoreOwnership(storeId, ownerId);

        // 2. 연월 검증 (기본값: 이번 달)
        LocalDate now = LocalDate.now();
        int year = now.getYear();
        int month = now.getMonthValue();

        // 요청에 날짜가 있으면 해당 날짜의 연월 사용
        if (requestDto.getDate() != null) {
            year = requestDto.getDate().getYear();
            month = requestDto.getDate().getMonthValue();
        }

        log.info("월별 통계 대상 - 연도: {}, 월: {}", year, month);

        // 3. 통계 데이터 조회
        Long monthlyPaymentAmount = transactionRepository.getMonthlyPaymentAmountByStore(storeId, year, month);
        Long monthlyTotalChargePoints = transactionRepository.getMonthlyTotalChargePointsByStore(storeId, year, month);
        Long monthlyPointsUsed = transactionRepository.getMonthlyPointsUsedByStore(storeId, year, month);
        Long monthlyChargeCount = transactionRepository.getMonthlyChargeCountByStore(storeId, year, month);
        Long monthlyUseCount = transactionRepository.getMonthlyUseCountByStore(storeId, year, month);
        Long monthlyTransactionCount = transactionRepository.getMonthlyTransactionCountByStore(storeId, year, month);

        // 4. 일평균 계산 (해당 월의 총 일수)
        LocalDate targetMonth = LocalDate.of(year, month, 1);
        int daysInMonth = targetMonth.lengthOfMonth();
        Long averageDailyPayment = daysInMonth > 0 ? monthlyPaymentAmount / daysInMonth : 0L;
        Long averageDailyPointsUsed = daysInMonth > 0 ? monthlyPointsUsed / daysInMonth : 0L;

        log.info("월별 통계 조회 완료 - 가게: {}, {}/{}월, 결제: {}원, 사용: {}포인트",
                store.getStoreName(), year, month, monthlyPaymentAmount, monthlyPointsUsed);

        return MonthlyStatisticsResponseDto.builder()
                .storeId(store.getStoreId())
                .storeName(store.getStoreName())
                .year(year)
                .month(month)
                .monthlyPaymentAmount(monthlyPaymentAmount)
                .monthlyTotalChargePoints(monthlyTotalChargePoints)
                .monthlyPointsUsed(monthlyPointsUsed)
                .monthlyTransactionCount(monthlyTransactionCount)
                .monthlyChargeCount(monthlyChargeCount)
                .monthlyUseCount(monthlyUseCount)
                .averageDailyPayment(averageDailyPayment)
                .averageDailyPointsUsed(averageDailyPointsUsed)
                .build();
    }

    /**
     * 가게 소유권 검증
     */
    private Store validateStoreOwnership(Long storeId, Long ownerId) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException(ErrorCode.STORE_NOT_FOUND));

        if (!store.getOwner().getOwnerId().equals(ownerId)) {
            log.warn("가게 소유권 없음 - 요청 점주ID: {}, 실제 점주ID: {}",
                    ownerId, store.getOwner().getOwnerId());
            throw new CustomException(ErrorCode.UNAUTHORIZED_ACCESS);
        }

        return store;
    }
}