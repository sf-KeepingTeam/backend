package com.ssafy.keeping.qr.domain.intent.service;

import com.ssafy.keeping.qr.acl.WalletClient;
import com.ssafy.keeping.qr.acl.dto.PaymentCheckResponse;
import com.ssafy.keeping.qr.acl.dto.RefundRequest;
import com.ssafy.keeping.qr.acl.dto.RefundResponse;
import com.ssafy.keeping.qr.common.alert.AlertService;
import com.ssafy.keeping.qr.domain.intent.constant.PaymentStatus;
import com.ssafy.keeping.qr.domain.intent.model.PaymentIntent;
import com.ssafy.keeping.qr.domain.intent.repository.PaymentIntentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 결제 에러 복구 서비스
 * - UNCERTAIN 상태의 결제에 대해 실제 결제 여부 확인 후 보상 트랜잭션 수행
 * - @Scheduled + DB 플래그 방식
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRecoveryService {

    private final PaymentIntentRepository paymentIntentRepository;
    private final WalletClient walletClient;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meterRegistry;
    private final AlertService alertService;

    private static final String RECOVERY_COUNTER = "payment_recovery_attempts_total";

    /** 복구 대상 존재 플래그 */
    private final AtomicBoolean hasRecoveryTarget = new AtomicBoolean(false);

    /** 중복 실행 방지 락 */
    private final AtomicBoolean isRecovering = new AtomicBoolean(false);

    /** 복구 대상 조회 범위 (최근 7일) */
    private static final int RECOVERY_DAYS = 7;

    /**
     * 서버 시작 시 복구 수행
     */
    @PostConstruct
    public void recoverOnStartup() {
        log.info("서버 시작 - 결제 복구 확인 시작");
        hasRecoveryTarget.set(true);
        recoverPeriodically();
    }

    /**
     * 복구 필요 플래그 설정 - FundsService에서 호출
     */
    public void markRecoveryNeeded() {
        hasRecoveryTarget.set(true);
        log.debug("복구 대상 플래그 설정됨");
    }

    /**
     * 주기적 복구 (10초 간격)
     * hasRecoveryTarget 플래그가 true일 때만 실제 복구 수행
     */
    @Scheduled(fixedRate = 10000)
    public void recoverPeriodically() {
        if (!hasRecoveryTarget.get()) {
            return;
        }

        if (!isRecovering.compareAndSet(false, true)) {
            log.debug("이미 복구 작업 진행 중 - 스킵");
            return;
        }

        try {
            doRecover();
        } finally {
            isRecovering.set(false);
        }
    }

    /**
     * 복구 대상 조회 및 개별 복구 수행
     */
    private void doRecover() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusDays(RECOVERY_DAYS);

        List<PaymentIntent> targets = paymentIntentRepository.findRecoveryTargets(
                PaymentStatus.UNCERTAIN,
                PaymentStatus.PENDING,
                now,
                since
        );

        if (targets.isEmpty()) {
            hasRecoveryTarget.set(false);
            log.debug("복구 대상 없음 - 플래그 해제");
            return;
        }

        log.info("복구 대상 {}건 발견", targets.size());

        for (PaymentIntent intent : targets) {
            try {
                recoverSinglePayment(intent);
            } catch (Exception e) {
                log.error("결제 복구 실패: intentId={}, error={}", intent.getIntentId(), e.getMessage());
            }
        }
    }

    /**
     * 개별 결제 복구 로직 (트랜잭션 분리)
     *
     * 핵심 변경: @Transactional 제거 → 외부 API 호출과 DB 저장 분리
     * - Phase 1: 외부 API 호출 (트랜잭션 없음, DB 커넥션 미점유)
     * - Phase 2: DB 저장 (짧은 트랜잭션)
     *
     * 이점:
     * - DB 커넥션 고갈 방지 (외부 API 대기 중 커넥션 점유 X)
     * - 복구용 RestTemplate 사용 (10초 타임아웃, 재시도 3회)
     */
    private static final int MAX_RETRY_COUNT = 10;

    public void recoverSinglePayment(PaymentIntent intent) {
        Long intentId = intent.getIntentId();

        // retryCount 증가 (별도 트랜잭션)
        int currentRetryCount = incrementRetryCount(intentId);

        // 복구 재시도 5회 이상 경고
        if (currentRetryCount >= 5 && intent.getStatus() == PaymentStatus.UNCERTAIN) {
            alertService.notifyRecoveryWarning(intent, currentRetryCount);
        }

        // 최대 재시도 초과 시 RECOVERY_FAILED로 전이하고 종료
        if (currentRetryCount > MAX_RETRY_COUNT) {
            markMaxRetryExceeded(intentId, currentRetryCount);
            meterRegistry.counter(RECOVERY_COUNTER, "result", "max_retry_exceeded").increment();
            return;
        }

        String idempotencyKey = generateIdempotencyKey(intent);

        log.info("결제 복구 시작: intentId={}, retryCount={}, idempotencyKey={}",
                intentId, currentRetryCount, idempotencyKey);

        // Phase 1: 외부 API 호출 (트랜잭션 없음)
        RecoveryResult result = performExternalRecovery(intent, idempotencyKey);

        try {
            // Phase 2: DB 저장 (짧은 트랜잭션)
            persistRecoveryResult(intentId, result);
            meterRegistry.counter(RECOVERY_COUNTER, "result", result.getKind()).increment();
        } catch (RuntimeException e) {
            // persistRecoveryResult 내부 재시도 throw 또는 Phase 2 자체 오류
            meterRegistry.counter(RECOVERY_COUNTER, "result", "persist_failure").increment();
            throw e;
        }
    }

    /**
     * 외부 API 호출 수행 (트랜잭션 없음)
     * - recoveryRestTemplate 사용 (10초 타임아웃)
     * - 재시도 3회 허용
     */
    private RecoveryResult performExternalRecovery(PaymentIntent intent, String idempotencyKey) {
        // 1. 실제 결제 여부 확인 (복구용 메서드 사용)
        PaymentCheckResponse checkResult = walletClient.checkPaymentForRecovery(idempotencyKey);

        if (checkResult == null || !checkResult.isExists()) {
            log.info("결제 미발생 확인: intentId={}", intent.getIntentId());
            return RecoveryResult.noPaymentFound("결제 미발생 확인 - 복구 완료");
        }

        // 2. 결제가 존재하면 환불 처리 (복구용 메서드 사용)
        String refundIdempotencyKey = generateRefundIdempotencyKey(intent);

        RefundRequest refundRequest = RefundRequest.builder()
                .walletId(intent.getWalletId())
                .storeId(intent.getStoreId())
                .amount(intent.getAmount())
                .originalTransactionId(checkResult.getTransactionId())
                .reason("UNCERTAIN 상태 자동 복구")
                .build();

        RefundResponse refundResult = walletClient.refundForRecovery(refundRequest, refundIdempotencyKey);

        if (refundResult != null && refundResult.isSuccess()) {
            log.info("결제 환불 완료: intentId={}, refundTxId={}",
                    intent.getIntentId(), refundResult.getRefundTransactionId());
            return RecoveryResult.refundSuccess("환불 완료 - txId: " + refundResult.getRefundTransactionId());
        } else if (refundResult != null && refundResult.isPermanent()) {
            log.error("환불 영구 실패 - 수동 확인 필요: intentId={}, message={}",
                    intent.getIntentId(), refundResult.getMessage());
            String reason = "환불 영구 실패: " + refundResult.getMessage();
            alertService.notifyRecoveryFailed(intent, reason);
            return RecoveryResult.refundPermanentlyFailed(
                    "영구 실패(수동 확인): " + refundResult.getMessage());
        } else {
            log.error("환불 일시 실패: intentId={}, response={}", intent.getIntentId(), refundResult);
            return RecoveryResult.refundFailed("환불 실패");
        }
    }

    /**
     * 복구 결과 DB 저장 (짧은 트랜잭션)
     * - transactionTemplate 사용으로 명시적 트랜잭션 경계
     * - 동시성 보호: 이미 처리된 경우 스킵
     */
    private void persistRecoveryResult(Long intentId, RecoveryResult result) {
        transactionTemplate.executeWithoutResult(status -> {
            PaymentIntent fresh = paymentIntentRepository.findById(intentId)
                    .orElseThrow(() -> new RuntimeException("PaymentIntent not found: " + intentId));

            // 동시성 보호: 이미 처리된 경우 스킵
            if (fresh.getStatus() != PaymentStatus.UNCERTAIN) {
                log.info("이미 처리됨 - 스킵: intentId={}, status={}", intentId, fresh.getStatus());
                return;
            }

            if (result.isSuccess()) {
                fresh.markRolledBack(LocalDateTime.now(), result.getNote());
                paymentIntentRepository.save(fresh);
                log.info("복구 결과 저장 완료: intentId={}", intentId);
            } else if (result.isPermanentFailure()) {
                fresh.markRecoveryFailed(LocalDateTime.now(), result.getNote());
                paymentIntentRepository.save(fresh);
                log.error("[RECOVERY_FAILED] intentId={} - 수동 확인 필요", intentId);
            } else {
                // 일시 실패 시 RuntimeException → 재시도 필요
                throw new RuntimeException("복구 실패 - 재시도 필요: " + result.getNote());
            }
        });
    }

    /**
     * retryCount 증가 (별도 짧은 트랜잭션)
     */
    private int incrementRetryCount(Long intentId) {
        return transactionTemplate.execute(status -> {
            PaymentIntent fresh = paymentIntentRepository.findById(intentId)
                    .orElseThrow(() -> new RuntimeException("PaymentIntent not found: " + intentId));
            int newCount = fresh.getRetryCount() + 1;
            fresh.setRetryCount(newCount);
            paymentIntentRepository.save(fresh);
            return newCount;
        });
    }

    /**
     * 최대 재시도 초과 시 RECOVERY_FAILED 전이
     */
    private void markMaxRetryExceeded(Long intentId, int retryCount) {
        transactionTemplate.executeWithoutResult(status -> {
            PaymentIntent fresh = paymentIntentRepository.findById(intentId)
                    .orElseThrow(() -> new RuntimeException("PaymentIntent not found: " + intentId));
            if (fresh.getStatus() != PaymentStatus.UNCERTAIN) {
                log.info("이미 처리됨 - 스킵: intentId={}, status={}", intentId, fresh.getStatus());
                return;
            }
            String reason = "최대 재시도 횟수 초과 (" + retryCount + "회)";
            fresh.markRecoveryFailed(LocalDateTime.now(), reason);
            paymentIntentRepository.save(fresh);
            log.error("[MAX_RETRY_EXCEEDED] intentId={} retryCount={} - 수동 확인 필요",
                    intentId, retryCount);
            alertService.notifyRecoveryFailed(fresh, reason);
        });
    }

    /**
     * 복구 결과 DTO
     * kind는 Prometheus Counter tag 용도
     */
    private record RecoveryResult(boolean success, String note, String kind) {
        static RecoveryResult noPaymentFound(String note) {
            return new RecoveryResult(true, note, "no_payment");
        }

        static RecoveryResult refundSuccess(String note) {
            return new RecoveryResult(true, note, "refund_success");
        }

        static RecoveryResult refundFailed(String note) {
            return new RecoveryResult(false, note, "refund_transient_failure");
        }

        static RecoveryResult refundPermanentlyFailed(String note) {
            return new RecoveryResult(false, note, "refund_permanent_failure");
        }

        boolean isSuccess() {
            return success;
        }

        boolean isPermanentFailure() {
            return !success && "refund_permanent_failure".equals(kind);
        }

        String getNote() {
            return note;
        }

        String getKind() {
            return kind;
        }
    }

    /**
     * PaymentIntent 기반 결정적 멱등성 키 생성
     */
    private String generateIdempotencyKey(PaymentIntent intent) {
        return UUID.nameUUIDFromBytes(
                ("capture:" + intent.getPublicId().toString()).getBytes()
        ).toString();
    }

    /**
     * 환불용 멱등성 키 생성
     */
    private String generateRefundIdempotencyKey(PaymentIntent intent) {
        return UUID.nameUUIDFromBytes(
                ("refund:" + intent.getPublicId().toString()).getBytes()
        ).toString();
    }
}
