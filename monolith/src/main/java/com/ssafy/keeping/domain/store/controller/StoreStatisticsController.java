package com.ssafy.keeping.domain.store.controller;

import com.ssafy.keeping.domain.auth.security.principal.UserPrincipal;
import com.ssafy.keeping.domain.store.dto.DailyStatisticsResponseDto;
import com.ssafy.keeping.domain.store.dto.MonthlyStatisticsResponseDto;
import com.ssafy.keeping.domain.store.dto.PeriodStatisticsResponseDto;
import com.ssafy.keeping.domain.store.dto.StatisticsRequestDto;
import com.ssafy.keeping.domain.store.dto.StoreOverallStatisticsResponseDto;
import com.ssafy.keeping.domain.store.service.StoreStatisticsService;
import com.ssafy.keeping.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

@RestController
@RequestMapping("/stores/{storeId}/statistics")
@RequiredArgsConstructor
@Slf4j
@Validated
public class StoreStatisticsController {

    private final StoreStatisticsService storeStatisticsService;

    /**
     * 가게 전체 누적 통계 조회
     */
    @GetMapping("/overall") // Post -> Get 으로 변경되었습니다!!!!!!!
    public ResponseEntity<ApiResponse<StoreOverallStatisticsResponseDto>> getOverallStatistics(
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserPrincipal principal) {
        Long ownerId = principal.id();

        log.info("전체 통계 조회 요청 - 가게ID: {}, 점주ID: {}", storeId, ownerId);

        StoreOverallStatisticsResponseDto responseDto = storeStatisticsService.getOverallStatistics(storeId, ownerId);

        return ResponseEntity.ok()
                .body(ApiResponse.success("가게 전체 통계가 조회되었습니다.", HttpStatus.OK.value(), responseDto));
    }

    /**
     * 가게 일별 통계 조회
     */
    @PostMapping("/daily")
    public ResponseEntity<ApiResponse<DailyStatisticsResponseDto>> getDailyStatistics(
            @PathVariable @Positive(message = "가게 ID는 양수여야 합니다.") Long storeId,
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody @Valid StatisticsRequestDto requestDto) {
        Long ownerId = principal.id();

        log.info("일별 통계 조회 요청 - 가게ID: {}, 점주ID: {}, 날짜: {}",
                storeId, ownerId, requestDto.getDate());

        DailyStatisticsResponseDto responseDto = storeStatisticsService.getDailyStatistics(storeId, ownerId, requestDto);

        return ResponseEntity.ok()
                .body(ApiResponse.success("가게 일별 통계가 조회되었습니다.", HttpStatus.OK.value(), responseDto));
    }

    /**
     * 가게 기간별 통계 조회
     */
    @PostMapping("/period")
    public ResponseEntity<ApiResponse<PeriodStatisticsResponseDto>> getPeriodStatistics(
            @PathVariable @Positive(message = "가게 ID는 양수여야 합니다.") Long storeId,
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody @Valid StatisticsRequestDto requestDto) {
        Long ownerId = principal.id();

        log.info("기간별 통계 조회 요청 - 가게ID: {}, 점주ID: {}, 기간: {} ~ {}",
                storeId, ownerId, requestDto.getStartDate(), requestDto.getEndDate());

        PeriodStatisticsResponseDto responseDto = storeStatisticsService.getPeriodStatistics(storeId, ownerId, requestDto);

        return ResponseEntity.ok()
                .body(ApiResponse.success("가게 기간별 통계가 조회되었습니다.", HttpStatus.OK.value(), responseDto));
    }

    /**
     * 가게 월별 통계 조회
     */
    @PostMapping("/monthly")
    public ResponseEntity<ApiResponse<MonthlyStatisticsResponseDto>> getMonthlyStatistics(
            @PathVariable @Positive(message = "가게 ID는 양수여야 합니다.") Long storeId,
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody @Valid StatisticsRequestDto requestDto) {
        Long ownerId = principal.id();

        log.info("월별 통계 조회 요청 - 가게ID: {}, 점주ID: {}, 날짜: {}",
                storeId, ownerId, requestDto.getDate());

        MonthlyStatisticsResponseDto responseDto = storeStatisticsService.getMonthlyStatistics(storeId, ownerId, requestDto);

        return ResponseEntity.ok()
                .body(ApiResponse.success("가게 월별 통계가 조회되었습니다.", HttpStatus.OK.value(), responseDto));
    }
}