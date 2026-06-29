package com.ssafy.keeping.domain.charge.service;

import com.ssafy.keeping.domain.charge.constant.RefundStatus;
import com.ssafy.keeping.domain.charge.dto.CancelReclaimResult;
import com.ssafy.keeping.domain.charge.dto.request.CancelRequestDto;
import com.ssafy.keeping.domain.charge.dto.response.CancelListResponseDto;
import com.ssafy.keeping.domain.charge.dto.response.CancelResponseDto;
import com.ssafy.keeping.domain.payment.toss.TossPaymentClient;
import com.ssafy.keeping.domain.payment.toss.dto.TossCancelRequest;
import com.ssafy.keeping.domain.payment.toss.dto.TossCancelResponse;
import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.user.customer.repository.CustomerRepository;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 선결제 취소 서비스 — Saga 오케스트레이터.
 * <p>
 * Phase A (CancelReclaimService, @Transactional): 로컬 포인트 회수 + cancel TX 저장
 * Phase B (이 클래스, 트랜잭션 밖): 토스 환불 호출 + 멱등키 재시도
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CancelService {

    private static final int MAX_TOSS_RETRY = 3;
    private static final long RETRY_BACKOFF_MS = 200;

    private final CustomerRepository customerRepository;
    private final TossPaymentClient tossPaymentClient;
    private final CancelReclaimService cancelReclaimService;

    @Transactional(readOnly = true)
    public Page<CancelListResponseDto> getCancelableTransactions(Long customerId, Pageable pageable) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new CustomException(ErrorCode.CUSTOMER_NOT_FOUND));

        log.info("[취소] 취소 가능한 거래 목록 조회 - 고객ID: {}", customerId);

        return Page.empty(pageable);
    }

    /**
     * 결제 취소 처리 — Saga (비-트랜잭션).
     * Phase A: 로컬 포인트 회수 (별도 빈, TX)
     * Phase B: 토스 환불 (TX 밖, 멱등키 재시도)
     */
    public CancelResponseDto cancelPayment(Long customerId, CancelRequestDto requestDto) {
        // ── Phase A: 로컬 포인트 회수 (트랜잭션 내, 외부 호출 0) ──
        CancelReclaimResult reclaimResult = cancelReclaimService.reclaimPoints(customerId, requestDto);

        log.info("[취소-PhaseB] 시작 - 토스 환불 호출, paymentKey: {}", reclaimResult.getPaymentKey());

        // ── Phase B: 토스 환불 (트랜잭션 밖, 멱등키 기반 재시도) ──
        TossCancelRequest tossCancelRequest = TossCancelRequest.builder()
                .cancelReason(requestDto.getCancelReason())
                .cancelAmount(requestDto.getCancelAmount() != null
                        ? requestDto.getCancelAmount()
                        : reclaimResult.getCancelAmount())
                .build();

        RefundStatus finalStatus = RefundStatus.REFUND_PENDING;
        Exception lastException = null;

        for (int attempt = 1; attempt <= MAX_TOSS_RETRY; attempt++) {
            try {
                TossCancelResponse tossResponse = tossPaymentClient.cancelPayment(
                        reclaimResult.getPaymentKey(), tossCancelRequest);

                if (tossResponse.isSuccess() || isAlreadyDone(tossResponse)) {
                    // 성공 또는 "이미 취소됨"(= 돈은 이미 환불됨) → REFUND_DONE
                    finalStatus = RefundStatus.REFUND_DONE;
                    cancelReclaimService.updateRefundStatus(
                            reclaimResult.getCancelTransactionId(), RefundStatus.REFUND_DONE);
                    log.info("[취소-PhaseB] 토스 환불 성공 - paymentKey: {}, attempt: {}, code: {}",
                            reclaimResult.getPaymentKey(), attempt, tossResponse.getCode());
                    break;
                }

                // 실패 응답 수신 — 에러 분류
                if (isPermanentFailure(tossResponse)) {
                    // 영구 실패 (환불 불가 등)
                    finalStatus = RefundStatus.REFUND_PERMANENT_FAILED;
                    cancelReclaimService.updateRefundStatus(
                            reclaimResult.getCancelTransactionId(), RefundStatus.REFUND_PERMANENT_FAILED);
                    log.error("[취소-PhaseB] 토스 환불 영구 실패 - code: {}, message: {}, paymentKey: {}",
                            tossResponse.getCode(), tossResponse.getMessage(), reclaimResult.getPaymentKey());
                    break;
                }

                // 일시 실패 → 재시도
                log.warn("[취소-PhaseB] 토스 환불 일시 실패 (attempt {}/{}) - code: {}, message: {}",
                        attempt, MAX_TOSS_RETRY, tossResponse.getCode(), tossResponse.getMessage());

            } catch (Exception e) {
                // 네트워크 타임아웃, 연결 실패 등 → 재시도
                lastException = e;
                log.warn("[취소-PhaseB] 토스 환불 예외 (attempt {}/{}) - {}",
                        attempt, MAX_TOSS_RETRY, e.getMessage());
            }

            // 마지막 시도가 아니면 backoff 후 재시도
            if (attempt < MAX_TOSS_RETRY) {
                try {
                    Thread.sleep(RETRY_BACKOFF_MS * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // ── 응답 분기: refund 상태에 따라 다른 응답 ──
        if (finalStatus == RefundStatus.REFUND_DONE) {
            return CancelResponseDto.builder()
                    .cancelTransactionId(reclaimResult.getCancelTransactionId())
                    .transactionUniqueNo(reclaimResult.getTransactionUniqueNo())
                    .cancelAmount(reclaimResult.getCancelAmount())
                    .cancelTime(reclaimResult.getCancelTime())
                    .remainingBalance(reclaimResult.getRemainingBalance())
                    .build();
        }

        if (finalStatus == RefundStatus.REFUND_PERMANENT_FAILED) {
            log.error("[취소-PhaseB] 포인트 회수 완료됐으나 토스 환불 영구 실패 - paymentKey: {}, 수동 확인 필요",
                    reclaimResult.getPaymentKey());
            throw new CustomException(ErrorCode.REFUND_FAILED_NEEDS_SUPPORT);
        }

        // REFUND_PENDING — 일시 실패로 재시도 소진
        log.error("[취소-PhaseB] 토스 환불 {}회 재시도 실패 - paymentKey: {}, REFUND_PENDING 유지. " +
                        "forward recovery 필요. lastException: {}",
                MAX_TOSS_RETRY, reclaimResult.getPaymentKey(),
                lastException != null ? lastException.getMessage() : "N/A");
        // TODO: @Scheduled 복구 잡으로 REFUND_PENDING 건 주기적 재시도 (forward recovery)
        throw new CustomException(ErrorCode.REFUND_PENDING);
    }

    /**
     * "이미 취소됨" 판정 — 돈이 이미 환불된 상태이므로 성공(REFUND_DONE)으로 처리해야 한다.
     * 이를 영구실패로 처리하면 운영자 재환불 → 이중환불 위험.
     */
    private boolean isAlreadyDone(TossCancelResponse response) {
        String code = response.getCode();
        return code != null
                && (code.startsWith("ALREADY_CANCELED") || code.contains("ALREADY_PROCESSED"));
    }

    /**
     * 토스 에러 코드 기반 영구 실패 판정.
     * 영구 실패 = 재시도해도 결과가 바뀌지 않는 에러 (4xx 계열).
     * 주의: ALREADY_CANCELED는 여기가 아니라 isAlreadyDone()에서 성공으로 처리.
     */
    private boolean isPermanentFailure(TossCancelResponse response) {
        String code = response.getCode();
        if (code == null) {
            return false;
        }
        return code.startsWith("NOT_CANCELABLE")
                || code.startsWith("INVALID_REQUEST")
                || code.startsWith("FORBIDDEN")
                || code.startsWith("NOT_FOUND")
                || code.equals("FORCE_FAIL");
    }
}
