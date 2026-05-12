package com.ssafy.keeping.qr.domain.intent.service;

import com.ssafy.keeping.qr.acl.WalletClient;
import com.ssafy.keeping.qr.acl.dto.FundsCaptureRequest;
import com.ssafy.keeping.qr.acl.dto.FundsResponse;
import com.ssafy.keeping.qr.common.exception.CustomException;
import com.ssafy.keeping.qr.common.exception.ErrorCode;
import com.ssafy.keeping.qr.domain.intent.constant.PaymentStatus;
import com.ssafy.keeping.qr.domain.intent.model.PaymentIntent;
import com.ssafy.keeping.qr.domain.intent.model.PaymentIntentItem;
import com.ssafy.keeping.qr.domain.intent.repository.PaymentIntentRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * 자금 서비스 - ACL을 통해 모놀리스의 Wallet 서비스 호출
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundsService {

    private final WalletClient walletClient;
    private final PaymentRecoveryService paymentRecoveryService;
    private final PaymentIntentRepository paymentIntentRepository;
    private final IntentStatusUpdater intentStatusUpdater;

    /**
     * 자금 캡처 (잔액 차감 + 거래 내역 생성)
     * PaymentIntent의 publicId를 기반으로 결정적 멱등성 키 생성하여 중복 결제 방지
     *
     * 타임아웃/서킷 오픈 시:
     * 1. Intent를 UNCERTAIN 상태로 변경
     * 2. PaymentRecoveryService에 복구 필요 플래그 설정
     * 3. Fast Fail - 즉시 에러 응답
     */
    public FundsResult capture(PaymentIntent intent, List<PaymentIntentItem> items) {
        String idempotencyKey = generateIdempotencyKey(intent);

        try {
            List<FundsCaptureRequest.ItemSnapshot> itemSnapshots = items.stream()
                    .map(item -> FundsCaptureRequest.ItemSnapshot.builder()
                            .menuId(item.getMenuId())
                            .menuName(item.getMenuNameSnap())
                            .unitPrice(item.getUnitPriceSnap())
                            .quantity(item.getQuantity())
                            .build())
                    .collect(Collectors.toList());

            FundsCaptureRequest request = FundsCaptureRequest.builder()
                    .walletId(intent.getWalletId())
                    .storeId(intent.getStoreId())
                    .customerId(intent.getCustomerId())
                    .amount(intent.getAmount())
                    .items(itemSnapshots)
                    .build();

            FundsResponse response = walletClient.capture(request, idempotencyKey);

            if (response == null) {
                return FundsResult.failed();
            }

            return new FundsResult(
                    response.isSufficient(),
                    response.isPolicyOk(),
                    response.getTransactionId(),
                    response.getErrorCode(),
                    false
            );

        } catch (CustomException e) {
            // 서비스 불가 예외 (타임아웃, 서킷 오픈 등) 처리
            if (isNetworkOrCircuitError(e)) {
                markIntentUncertain(intent, determineFailureReason(e));
                log.warn("자금 캡처 타임아웃/서킷 오픈 - UNCERTAIN 상태로 변경: intentId={}, reason={}",
                        intent.getIntentId(), determineFailureReason(e));
                return FundsResult.uncertain();
            }
            log.error("자금 캡처 실패: intentId={}, error={}", intent.getIntentId(), e.getMessage());
            throw e;
        } catch (Exception e) {
            // 네트워크 타임아웃 등 예상치 못한 예외
            if (isTimeoutException(e)) {
                markIntentUncertain(intent, "TIMEOUT");
                log.warn("자금 캡처 타임아웃 - UNCERTAIN 상태로 변경: intentId={}", intent.getIntentId());
                return FundsResult.uncertain();
            }
            log.error("자금 캡처 실패: intentId={}, error={}", intent.getIntentId(), e.getMessage());
            return FundsResult.failed();
        }
    }

    /**
     * Intent를 UNCERTAIN 상태로 변경하고 복구 플래그 설정
     */
    private void markIntentUncertain(PaymentIntent intent, String reason) {
        try {
            intentStatusUpdater.markUncertain(intent.getIntentId(), reason);
            paymentRecoveryService.markRecoveryNeeded();
        } catch (Exception e) {
            log.error("Intent UNCERTAIN 상태 변경 실패: intentId={}, error={}", intent.getIntentId(), e.getMessage());
        }
    }

    /**
     * 네트워크 또는 서킷 브레이커 관련 에러인지 확인
     */
    private boolean isNetworkOrCircuitError(CustomException e) {
        ErrorCode code = e.getErrorCode();
        return code == ErrorCode.WALLET_SERVICE_UNAVAILABLE
                || code == ErrorCode.SERVICE_TIMEOUT
                || code == ErrorCode.CIRCUIT_BREAKER_OPEN
                || code == ErrorCode.MONOLITH_UNAVAILABLE;
    }

    /**
     * 타임아웃 예외인지 확인
     */
    private boolean isTimeoutException(Exception e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof TimeoutException
                    || cause instanceof ResourceAccessException
                    || cause instanceof CallNotPermittedException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    /**
     * 실패 원인 결정
     */
    private String determineFailureReason(CustomException e) {
        Throwable cause = e.getCause();
        while (cause != null) {
            if (cause instanceof CallNotPermittedException) {
                return "CIRCUIT_OPEN";
            }
            cause = cause.getCause();
        }
        return "TIMEOUT";
    }

    /**
     * PaymentIntent 기반 결정적 멱등성 키 생성
     * 동일 intent에 대한 재시도 시 동일한 키가 생성됨
     */
    private String generateIdempotencyKey(PaymentIntent intent) {
        return UUID.nameUUIDFromBytes(
                ("capture:" + intent.getPublicId().toString()).getBytes()
        ).toString();
    }

    /**
     * 자금 복원 (결제 취소 시)
     */
    public void restore(Long walletId, Long storeId, Long amount) {
        walletClient.restore(walletId, storeId, amount);
    }

    public static class FundsResult {
        private final boolean sufficient;
        private final boolean policyOk;
        private final Long transactionId;
        private final String errorCode;
        private final boolean uncertain;

        public FundsResult(boolean sufficient, boolean policyOk, Long transactionId, String errorCode, boolean uncertain) {
            this.sufficient = sufficient;
            this.policyOk = policyOk;
            this.transactionId = transactionId;
            this.errorCode = errorCode;
            this.uncertain = uncertain;
        }

        public static FundsResult insufficient() {
            return new FundsResult(false, true, null, null, false);
        }

        public static FundsResult policyViolation() {
            return new FundsResult(true, false, null, null, false);
        }

        public static FundsResult failed() {
            return new FundsResult(false, false, null, null, false);
        }

        public static FundsResult ok(Long txId) {
            return new FundsResult(true, true, txId, null, false);
        }

        public static FundsResult uncertain() {
            return new FundsResult(false, false, null, null, true);
        }

        public boolean isSufficient() {
            return sufficient;
        }

        public boolean isPolicyOk() {
            return policyOk;
        }

        public Long getTransactionId() {
            return transactionId;
        }

        public String getErrorCode() {
            return errorCode;
        }

        public boolean isUncertain() {
            return uncertain;
        }
    }
}
