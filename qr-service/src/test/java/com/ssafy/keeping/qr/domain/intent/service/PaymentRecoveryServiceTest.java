package com.ssafy.keeping.qr.domain.intent.service;

import com.ssafy.keeping.qr.acl.WalletClient;
import com.ssafy.keeping.qr.acl.dto.PaymentCheckResponse;
import com.ssafy.keeping.qr.acl.dto.RefundRequest;
import com.ssafy.keeping.qr.acl.dto.RefundResponse;
import com.ssafy.keeping.qr.common.exception.CustomException;
import com.ssafy.keeping.qr.common.exception.ErrorCode;
import com.ssafy.keeping.qr.domain.intent.constant.PaymentStatus;
import com.ssafy.keeping.qr.domain.intent.model.PaymentIntent;
import com.ssafy.keeping.qr.domain.intent.repository.PaymentIntentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.transaction.support.TransactionCallback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentRecoveryServiceTest {

    private PaymentIntentRepository paymentIntentRepository;
    private WalletClient walletClient;
    private TransactionTemplate transactionTemplate;
    private MeterRegistry meterRegistry;
    private PaymentRecoveryService service;

    @BeforeEach
    void setUp() {
        paymentIntentRepository = mock(PaymentIntentRepository.class);
        walletClient = mock(WalletClient.class);
        transactionTemplate = mock(TransactionTemplate.class);
        meterRegistry = new SimpleMeterRegistry();
        service = new PaymentRecoveryService(
                paymentIntentRepository,
                walletClient,
                transactionTemplate,
                meterRegistry
        );

        // TransactionTemplate.executeWithoutResult: 인자 Consumer를 즉시 실행하도록 stub
        doAnswer(invocation -> {
            Consumer<TransactionStatus> callback = invocation.getArgument(0);
            callback.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        // TransactionTemplate.execute: 인자 TransactionCallback을 즉시 실행하도록 stub
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        }).when(transactionTemplate).execute(any());
    }

    @Test
    void refund가_4xx_영구실패_반환하면_RECOVERY_FAILED_전이하고_permanent_counter_증가() {
        // given
        PaymentIntent intent = buildUncertainIntent(100L);
        when(walletClient.checkPaymentForRecovery(anyString()))
                .thenReturn(PaymentCheckResponse.builder()
                        .exists(true)
                        .transactionId(999L)
                        .build());
        when(walletClient.refundForRecovery(any(RefundRequest.class), anyString()))
                .thenReturn(RefundResponse.permanentFailed(401, "Unauthorized"));
        when(paymentIntentRepository.findById(100L)).thenReturn(Optional.of(intent));

        // when
        service.recoverSinglePayment(intent);

        // then
        assertThat(intent.getStatus()).isEqualTo(PaymentStatus.RECOVERY_FAILED);
        assertThat(intent.getRecoveryNote()).contains("영구 실패");
        verify(paymentIntentRepository, atLeast(1)).save(intent);

        double permanentCount = meterRegistry.counter(
                "payment_recovery_attempts_total", "result", "refund_permanent_failure").count();
        assertThat(permanentCount).isEqualTo(1.0);
    }

    @Test
    void refund_5xx_timeout_fallback은_CustomException_throw_persist_failure_counter_증가() {
        // given
        PaymentIntent intent = buildUncertainIntent(200L);
        when(paymentIntentRepository.findById(200L)).thenReturn(Optional.of(intent));
        when(walletClient.checkPaymentForRecovery(anyString()))
                .thenReturn(PaymentCheckResponse.builder()
                        .exists(true)
                        .transactionId(999L)
                        .build());
        // 5xx/timeout 경로: fallback이 CustomException throw (실제 WalletClient 동작)
        when(walletClient.refundForRecovery(any(RefundRequest.class), anyString()))
                .thenThrow(new CustomException(ErrorCode.WALLET_SERVICE_UNAVAILABLE));

        // when / then: performExternalRecovery 내부에서 exception 전파
        assertThatThrownBy(() -> service.recoverSinglePayment(intent))
                .isInstanceOf(CustomException.class);

        // Intent 상태는 그대로 UNCERTAIN 유지 (persist 도달 안 함)
        assertThat(intent.getStatus()).isEqualTo(PaymentStatus.UNCERTAIN);
    }

    @Test
    void check가_exists_false면_no_payment로_ROLLED_BACK() {
        // given
        PaymentIntent intent = buildUncertainIntent(300L);
        when(walletClient.checkPaymentForRecovery(anyString()))
                .thenReturn(PaymentCheckResponse.builder().exists(false).build());
        when(paymentIntentRepository.findById(300L)).thenReturn(Optional.of(intent));

        // when
        service.recoverSinglePayment(intent);

        // then
        assertThat(intent.getStatus()).isEqualTo(PaymentStatus.ROLLED_BACK);
        verify(paymentIntentRepository, atLeast(1)).save(intent);

        double noPaymentCount = meterRegistry.counter(
                "payment_recovery_attempts_total", "result", "no_payment").count();
        assertThat(noPaymentCount).isEqualTo(1.0);

        // refund는 호출되지 않음
        ArgumentCaptor<RefundRequest> captor = ArgumentCaptor.forClass(RefundRequest.class);
        verify(walletClient, org.mockito.Mockito.never()).refundForRecovery(captor.capture(), anyString());
    }

    @Test
    void refund_성공_시_refund_success_counter_증가() {
        // given
        PaymentIntent intent = buildUncertainIntent(400L);
        when(walletClient.checkPaymentForRecovery(anyString()))
                .thenReturn(PaymentCheckResponse.builder()
                        .exists(true)
                        .transactionId(999L)
                        .build());
        when(walletClient.refundForRecovery(any(RefundRequest.class), anyString()))
                .thenReturn(RefundResponse.builder()
                        .success(true)
                        .refundTransactionId(12345L)
                        .build());
        when(paymentIntentRepository.findById(400L)).thenReturn(Optional.of(intent));

        // when
        service.recoverSinglePayment(intent);

        // then
        assertThat(intent.getStatus()).isEqualTo(PaymentStatus.ROLLED_BACK);
        double successCount = meterRegistry.counter(
                "payment_recovery_attempts_total", "result", "refund_success").count();
        assertThat(successCount).isEqualTo(1.0);
    }

    @Test
    void retryCount가_10_초과하면_복구시도_없이_RECOVERY_FAILED_전이() {
        // given
        PaymentIntent intent = buildUncertainIntent(500L);
        intent.setRetryCount(10); // 현재 10 → incrementRetryCount에서 11로 증가
        when(paymentIntentRepository.findById(500L)).thenReturn(Optional.of(intent));

        // when
        service.recoverSinglePayment(intent);

        // then
        assertThat(intent.getStatus()).isEqualTo(PaymentStatus.RECOVERY_FAILED);
        assertThat(intent.getRecoveryNote()).contains("최대 재시도 횟수 초과");
        verify(walletClient, never()).checkPaymentForRecovery(anyString());

        double maxRetryCount = meterRegistry.counter(
                "payment_recovery_attempts_total", "result", "max_retry_exceeded").count();
        assertThat(maxRetryCount).isEqualTo(1.0);
    }

    private PaymentIntent buildUncertainIntent(Long id) {
        LocalDateTime now = LocalDateTime.now();
        return PaymentIntent.builder()
                .intentId(id)
                .publicId(UUID.randomUUID())
                .qrTokenId("token-" + id)
                .customerId(1L)
                .walletId(10L)
                .storeId(100L)
                .amount(5000L)
                .status(PaymentStatus.UNCERTAIN)
                .createdAt(now.minusMinutes(5))
                .updatedAt(now)
                .expiresAt(now.minusMinutes(2))
                .build();
    }
}
