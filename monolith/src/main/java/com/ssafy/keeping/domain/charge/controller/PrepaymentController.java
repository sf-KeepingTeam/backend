package com.ssafy.keeping.domain.charge.controller;

import com.ssafy.keeping.domain.auth.security.principal.UserPrincipal;
import com.ssafy.keeping.domain.charge.dto.request.PrepaymentConfirmRequest;
import com.ssafy.keeping.domain.charge.dto.request.PrepaymentReserveRequest;
import com.ssafy.keeping.domain.charge.dto.response.PrepaymentReserveResponse;
import com.ssafy.keeping.domain.charge.dto.response.PrepaymentResponseDto;
import com.ssafy.keeping.domain.charge.service.PrepaymentService;
import com.ssafy.keeping.domain.idempotency.model.IdempotentResult;
import com.ssafy.keeping.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

@RestController
@RequestMapping("/api/v1/stores")
@RequiredArgsConstructor
@Slf4j
@Validated
public class PrepaymentController {

    private final PrepaymentService prepaymentService;

    /**
     * [1단계] 선결제 예약 (보안 강화)
     * 서버에서 금액을 먼저 확정하여 금액 변조 방지
     *
     * @param storeId 가게 ID
     * @param principal 인증 정보
     * @param request 예약 요청 (금액)
     * @return 예약 정보 (orderId, amount, expiresAt)
     */
    @PostMapping("/{storeId}/prepayment/reserve")
    public ResponseEntity<ApiResponse<PrepaymentReserveResponse>> reservePrepayment(
            @PathVariable @Positive(message = "가게 ID는 양수여야 합니다.") Long storeId,
             @AuthenticationPrincipal UserPrincipal principal,  // 실제 운영용
//            @RequestParam(required = false, defaultValue = "1") Long customerId,  // 테스트용
            @RequestBody @Valid PrepaymentReserveRequest request) {
        Long customerId = principal.id();

//        log.info("[예약] 요청 수신 - 가게ID: {}, 고객ID: {}, 금액: {}원",
//                storeId, customerId, request.getAmount());

        PrepaymentReserveResponse response = prepaymentService.reservePayment(storeId, customerId, request);

//        log.info("[예약] 응답 전송 - orderId: {}, 만료: {}", response.getOrderId(), response.getExpiresAt());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("결제 예약이 생성되었습니다.", HttpStatus.CREATED.value(), response));
    }

    /**
     * [2단계] 프론트엔드 - 토스 결제창 호출
     * 성공하면 3단계로 호출
     */

    /**
     * [3단계] 선결제 승인 (보안 강화)
     * 예약된 금액과 비교하여 변조 방지 및 멱등성 보장
     *
     * @param storeId 가게 ID
     * @param principal 인증 정보
     * @param request 승인 요청 (paymentKey, orderId, amount)
     * @return 결제 결과
     */
    @PostMapping("/{storeId}/prepayment/confirm")
    public ResponseEntity<ApiResponse<PrepaymentResponseDto>> confirmPrepayment(
            @PathVariable @Positive(message = "가게 ID는 양수여야 합니다.") Long storeId,
             @AuthenticationPrincipal UserPrincipal principal,  // 실제 운영용
//            @RequestParam(required = false, defaultValue = "1") Long customerId,  // 테스트용
            @RequestBody @Valid PrepaymentConfirmRequest request) {
        Long customerId = principal.id();

//        log.info("[승인] 요청 수신 - 가게ID: {}, 고객ID: {}, orderId: {}, 금액: {}원",
//                storeId, customerId, request.getOrderId(), request.getAmount());

        IdempotentResult<PrepaymentResponseDto> result = prepaymentService.confirmPayment(storeId, customerId, request);

        PrepaymentResponseDto responseDto = result.getBody();
        HttpStatus httpStatus = result.getHttpStatus();

        String message;
        if (result.isReplay()) {
            message = "이전에 처리된 결제 결과를 반환합니다.";
//            log.info("[승인] 멱등성 재생 - 거래ID: {}", responseDto.getTransactionId());
        } else {
            message = "선결제가 성공적으로 완료되었습니다.";
//            log.info("[승인] 처리 성공 - 거래ID: {}, 포인트: {}P",
//                    responseDto.getTransactionId(), responseDto.getTotalPoints());
        }

        return ResponseEntity.status(httpStatus)
                .body(ApiResponse.success(message, httpStatus.value(), responseDto));
    }
}