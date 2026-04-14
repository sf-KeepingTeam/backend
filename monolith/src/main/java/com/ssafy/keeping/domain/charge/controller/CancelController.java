package com.ssafy.keeping.domain.charge.controller;

import com.ssafy.keeping.domain.auth.security.principal.UserPrincipal;
import com.ssafy.keeping.domain.charge.dto.request.CancelRequestDto;
import com.ssafy.keeping.domain.charge.dto.response.CancelListResponseDto;
import com.ssafy.keeping.domain.charge.dto.response.CancelResponseDto;
import com.ssafy.keeping.domain.charge.service.CancelService;
import com.ssafy.keeping.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

@RestController
@RequestMapping("/api/v1/customers")
@RequiredArgsConstructor
@Slf4j
@Validated
public class CancelController {

    private final CancelService cancelService;

    /**
     * 취소 가능한 거래 목록 조회 (페이지네이션)
     * @param principal
     * @param pageable 페이지네이션 정보 (기본: page=0, size=10, sort=createdAt,desc)
     * @return 취소 가능한 거래 목록
     */
    @GetMapping("/cancel-list")
    public ResponseEntity<ApiResponse<Page<CancelListResponseDto>>> getCancelableTransactions(
            @AuthenticationPrincipal UserPrincipal principal,
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {

        Long customerId = principal.id();

        log.info("취소 가능한 거래 목록 조회 요청 - 고객ID: {}, 페이지: {}, 크기: {}",
                customerId, pageable.getPageNumber(), pageable.getPageSize());

        Page<CancelListResponseDto> cancelableTransactions = cancelService
                .getCancelableTransactions(customerId, pageable);
        
        log.info("취소 가능한 거래 목록 조회 완료 - 전체 요소 수: {}, 현재 페이지 요소 수: {}", 
                cancelableTransactions.getTotalElements(), cancelableTransactions.getNumberOfElements());

        return ResponseEntity.ok(
                ApiResponse.success(
                    "취소 가능한 거래 목록 조회가 완료되었습니다.", 
                    HttpStatus.OK.value(), 
                    cancelableTransactions
                )
        );
    }

    /**
     * 결제 취소 처리
     *
     * @param cancelRequestDto 취소 요청 정보 (paymentKey 또는 transactionUniqueNo, cancelReason)
     * @return 취소 처리 결과
     */
    @PostMapping("/payments/cancel")
    public ResponseEntity<ApiResponse<CancelResponseDto>> cancelPayment(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestBody @Valid CancelRequestDto cancelRequestDto) {

        Long customerId = principal.id();

        String identifier = cancelRequestDto.getPaymentKey() != null
                ? cancelRequestDto.getPaymentKey()
                : cancelRequestDto.getTransactionUniqueNo();

        log.info("결제 취소 요청 - 고객ID: {}, 결제키/거래번호: {}, 취소사유: {}",
                customerId, identifier, cancelRequestDto.getCancelReason());

        CancelResponseDto response = cancelService.cancelPayment(customerId, cancelRequestDto);

        log.info("결제 취소 완료 - 취소 거래ID: {}", response.getCancelTransactionId());

        return ResponseEntity.ok(
                ApiResponse.success(
                    "결제 취소가 성공적으로 완료되었습니다.",
                    HttpStatus.OK.value(),
                    response
                )
        );
    }
}