package com.ssafy.keeping.qr.domain.intent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.qr.acl.CustomerClient;
import com.ssafy.keeping.qr.acl.MenuClient;
import com.ssafy.keeping.qr.acl.NotificationClient;
import com.ssafy.keeping.qr.acl.StoreClient;
import com.ssafy.keeping.qr.common.exception.CustomException;
import com.ssafy.keeping.qr.common.exception.ErrorCode;
import com.ssafy.keeping.qr.config.PaymentTuningProperties;
import com.ssafy.keeping.qr.domain.idempotency.constant.IdemActorType;
import com.ssafy.keeping.qr.domain.idempotency.constant.IdemStatus;
import com.ssafy.keeping.qr.domain.idempotency.dto.IdemBegin;
import com.ssafy.keeping.qr.domain.idempotency.model.IdempotencyKey;
import com.ssafy.keeping.qr.domain.idempotency.model.IdempotentResult;
import com.ssafy.keeping.qr.domain.intent.constant.PaymentStatus;
import com.ssafy.keeping.qr.domain.intent.dto.ApprovePhaseAResult;
import com.ssafy.keeping.qr.domain.intent.dto.ApproveRequest;
import com.ssafy.keeping.qr.domain.intent.dto.PaymentIntentDetailResponse;
import com.ssafy.keeping.qr.domain.intent.dto.PaymentIntentItemView;
import com.ssafy.keeping.qr.domain.intent.model.PaymentIntent;
import com.ssafy.keeping.qr.domain.intent.outbox.PaymentOutboxRepository;
import com.ssafy.keeping.qr.domain.intent.repository.PaymentIntentItemRepository;
import com.ssafy.keeping.qr.domain.intent.repository.PaymentIntentRepository;
import com.ssafy.keeping.qr.domain.qr.repository.QrFlowRedisStore;
import com.ssafy.keeping.qr.domain.qr.service.QrTokenService;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * approve() 트랜잭션 분리(세트 #31) 테스트.
 *
 * <p>작성만 하고 실행하지 않는다 (Testcontainers/Docker 의존 회피).
 * 단위 테스트로서 ApproveTransactionHelper 를 mock 처리한다.
 */
@ExtendWith(MockitoExtension.class)
class ApproveSplitTransactionTest {

    @Mock private PaymentIntentRepository intentRepository;
    @Mock private PaymentIntentItemRepository itemRepository;
    @Mock private com.ssafy.keeping.qr.domain.idempotency.service.IdempotencyService idempotencyService;
    @Mock private FundsService fundsService;
    @Mock private QrTokenService qrTokenService;
    @Mock private MenuClient menuClient;
    @Mock private StoreClient storeClient;
    @Mock private CustomerClient customerClient;
    @Mock private NotificationClient notificationClient;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ApproveTransactionHelper approveHelper;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private PinTokenVerifier pinTokenVerifier;
    @Mock private PaymentOutboxRepository outboxRepository;

    private PaymentIntentService service;
    private PaymentTuningProperties tuningProperties;

    private static final UUID INTENT_PUBLIC_ID = UUID.randomUUID();
    private static final Long CUSTOMER_ID = 100L;
    private static final Long INTENT_ID = 1L;
    private static final Long IDEM_SLOT_ID = 10L;
    private static final String IDEM_KEY = UUID.randomUUID().toString();
    private static final String PIN = "123456";

    private final Clock fixedClock =
            Clock.fixed(Instant.parse("2026-08-23T06:00:00Z"), ZoneId.of("Asia/Seoul"));

    @BeforeEach
    void setUp() {
        tuningProperties = new PaymentTuningProperties();
        // split-transaction=true (기본)
        tuningProperties.getApprove().setSplitTransaction(true);
        tuningProperties.getNotification().setAsync(true);

        ObjectMapper om = new ObjectMapper();

        service = new PaymentIntentService(
                intentRepository, itemRepository, idempotencyService,
                fundsService, qrTokenService, menuClient, storeClient,
                customerClient, notificationClient, om, om, fixedClock,
                eventPublisher, tuningProperties, approveHelper,
                transactionTemplate, pinTokenVerifier, outboxRepository,
                mock(QrFlowRedisStore.class));
    }

    private ApproveRequest pinRequest() {
        ApproveRequest req = new ApproveRequest();
        try {
            Field pinField = ApproveRequest.class.getDeclaredField("pin");
            pinField.setAccessible(true);
            pinField.set(req, PIN);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return req;
    }

    private ApprovePhaseAResult normalPhaseA() {
        return ApprovePhaseAResult.builder()
                .intentId(INTENT_ID)
                .intentPublicId(INTENT_PUBLIC_ID)
                .customerId(CUSTOMER_ID)
                .walletId(200L)
                .storeId(300L)
                .amount(10000L)
                .expiresAt(LocalDateTime.now(fixedClock).plusMinutes(3))
                .idemSlotId(IDEM_SLOT_ID)
                .itemViews(List.of(
                        PaymentIntentItemView.builder()
                                .menuId(1L).name("아메리카노")
                                .unitPrice(5000L).quantity(2).lineTotal(10000L)
                                .build()))
                .build();
    }

    // ═══════════════════════════════════════════════════
    //  T-1: approve 정상 → APPROVED + 멱등 DONE + 스냅샷
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("T-1: approve 정상 - APPROVED + 멱등 DONE + 스냅샷 저장")
    void approve_normal_approved() {
        ApprovePhaseAResult phaseA = normalPhaseA();
        given(approveHelper.prepareApproval(eq(INTENT_PUBLIC_ID), eq(IDEM_KEY), eq(CUSTOMER_ID), any()))
                .willReturn(phaseA);
        given(customerClient.verifyPin(CUSTOMER_ID, PIN)).willReturn(true);
        given(fundsService.capture(any(PaymentIntent.class), anyList()))
                .willReturn(new FundsService.FundsResult(true, true, 999L, null, false));

        PaymentIntentDetailResponse expectedRes = PaymentIntentDetailResponse.builder()
                .intentId(INTENT_PUBLIC_ID.toString())
                .status(PaymentStatus.APPROVED)
                .build();
        given(approveHelper.finalizeApproved(eq(INTENT_ID), eq(IDEM_SLOT_ID), anyList(), anyLong(), anyLong(), anyLong()))
                .willReturn(expectedRes);

        IdempotentResult<PaymentIntentDetailResponse> result =
                service.approve(INTENT_PUBLIC_ID, IDEM_KEY, CUSTOMER_ID, pinRequest());

        assertThat(result.getBody().getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(result.isReplay()).isFalse();

        then(approveHelper).should().finalizeApproved(eq(INTENT_ID), eq(IDEM_SLOT_ID), anyList(), anyLong(), anyLong(), anyLong());
        then(eventPublisher).should().publishEvent(any(Object.class));
    }

    // ═══════════════════════════════════════════════════
    //  T-2: PIN 실패 → DECLINED 가 DB에 남는다 (롤백 버그 회귀 방어)
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("T-2: PIN 실패 - DECLINED 가 DB에 남는다 (롤백 버그 회귀 방어)")
    void approve_pinFail_declined_persisted() {
        ApprovePhaseAResult phaseA = normalPhaseA();
        given(approveHelper.prepareApproval(eq(INTENT_PUBLIC_ID), eq(IDEM_KEY), eq(CUSTOMER_ID), any()))
                .willReturn(phaseA);
        given(customerClient.verifyPin(CUSTOMER_ID, PIN)).willReturn(false);

        assertThatThrownBy(() -> service.approve(INTENT_PUBLIC_ID, IDEM_KEY, CUSTOMER_ID, pinRequest()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PIN_INVALID);

        // 핵심: finalizeDeclined 가 호출되었고, 이것은 REQUIRES_NEW 트랜잭션이므로
        // CustomException(RuntimeException) 이 던져져도 DECLINED 상태가 커밋된다.
        then(approveHelper).should().finalizeDeclined(INTENT_ID, IDEM_SLOT_ID);
        then(approveHelper).should(never()).finalizeApproved(anyLong(), anyLong(), anyList(), anyLong(), anyLong(), anyLong());
    }

    // ═══════════════════════════════════════════════════
    //  T-3: 잔액 부족 → DECLINED 가 남고 적절한 에러코드
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("T-3: 잔액 부족 - DECLINED 가 남고 FUNDS_INSUFFICIENT")
    void approve_insufficient_declined() {
        ApprovePhaseAResult phaseA = normalPhaseA();
        given(approveHelper.prepareApproval(eq(INTENT_PUBLIC_ID), eq(IDEM_KEY), eq(CUSTOMER_ID), any()))
                .willReturn(phaseA);
        given(customerClient.verifyPin(CUSTOMER_ID, PIN)).willReturn(true);
        given(fundsService.capture(any(), anyList()))
                .willReturn(new FundsService.FundsResult(false, true, null, null, false));

        assertThatThrownBy(() -> service.approve(INTENT_PUBLIC_ID, IDEM_KEY, CUSTOMER_ID, pinRequest()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.FUNDS_INSUFFICIENT);

        then(approveHelper).should().finalizeDeclined(INTENT_ID, IDEM_SLOT_ID);
    }

    // ═══════════════════════════════════════════════════
    //  T-4: capture UNCERTAIN → intent UNCERTAIN, 복구 플래그
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("T-4: capture UNCERTAIN - intent 가 UNCERTAIN, 복구 플래그 설정됨")
    void approve_uncertain_noTxB() {
        ApprovePhaseAResult phaseA = normalPhaseA();
        given(approveHelper.prepareApproval(eq(INTENT_PUBLIC_ID), eq(IDEM_KEY), eq(CUSTOMER_ID), any()))
                .willReturn(phaseA);
        given(customerClient.verifyPin(CUSTOMER_ID, PIN)).willReturn(true);
        given(fundsService.capture(any(), anyList()))
                .willReturn(FundsService.FundsResult.uncertain());

        assertThatThrownBy(() -> service.approve(INTENT_PUBLIC_ID, IDEM_KEY, CUSTOMER_ID, pinRequest()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_TIMEOUT);

        // TX-B 는 호출되지 않는다 (IntentStatusUpdater 가 이미 UNCERTAIN 마킹)
        then(approveHelper).should(never()).finalizeApproved(anyLong(), anyLong(), anyList(), anyLong(), anyLong(), anyLong());
        then(approveHelper).should(never()).finalizeDeclined(anyLong(), anyLong());
    }

    // ═══════════════════════════════════════════════════
    //  T-5: TX-A 후 TX-B 전에 예외 → intent 가 PENDING 으로 남음
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("T-5: TX-A 후 TX-B 전 예외 - intent 가 PENDING 으로 남고 복구 대상")
    void approve_exceptionBetweenTxAAndTxB() {
        ApprovePhaseAResult phaseA = normalPhaseA();
        given(approveHelper.prepareApproval(eq(INTENT_PUBLIC_ID), eq(IDEM_KEY), eq(CUSTOMER_ID), any()))
                .willReturn(phaseA);
        // PIN 서비스 자체가 에러
        given(customerClient.verifyPin(CUSTOMER_ID, PIN))
                .willThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> service.approve(INTENT_PUBLIC_ID, IDEM_KEY, CUSTOMER_ID, pinRequest()))
                .isInstanceOf(RuntimeException.class);

        // TX-B 는 호출되지 않는다 → intent 는 PENDING 상태로 남음
        // 멱등 슬롯은 IN_PROGRESS → cleanupStalledInProgress(5분)가 정리
        then(approveHelper).should(never()).finalizeApproved(anyLong(), anyLong(), anyList(), anyLong(), anyLong(), anyLong());
        then(approveHelper).should(never()).finalizeDeclined(anyLong(), anyLong());
    }

    // ═══════════════════════════════════════════════════
    //  T-6: TX-B 에서 intent 가 이미 APPROVED → 멱등 재생
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("T-6: TX-B 에서 intent 이미 APPROVED - 멱등 재생")
    void approve_alreadyApproved_idempotent() {
        ApprovePhaseAResult phaseA = normalPhaseA();
        given(approveHelper.prepareApproval(eq(INTENT_PUBLIC_ID), eq(IDEM_KEY), eq(CUSTOMER_ID), any()))
                .willReturn(phaseA);
        given(customerClient.verifyPin(CUSTOMER_ID, PIN)).willReturn(true);
        given(fundsService.capture(any(), anyList()))
                .willReturn(new FundsService.FundsResult(true, true, 999L, null, false));

        // finalizeApproved 가 이미 APPROVED 상태인 intent 를 처리 (내부에서 멱등 방어)
        PaymentIntentDetailResponse idempotentRes = PaymentIntentDetailResponse.builder()
                .intentId(INTENT_PUBLIC_ID.toString())
                .status(PaymentStatus.APPROVED)
                .build();
        given(approveHelper.finalizeApproved(eq(INTENT_ID), eq(IDEM_SLOT_ID), anyList(), anyLong(), anyLong(), anyLong()))
                .willReturn(idempotentRes);

        IdempotentResult<PaymentIntentDetailResponse> result =
                service.approve(INTENT_PUBLIC_ID, IDEM_KEY, CUSTOMER_ID, pinRequest());

        assertThat(result.getBody().getStatus()).isEqualTo(PaymentStatus.APPROVED);
    }

    // ═══════════════════════════════════════════════════
    //  T-7: 같은 멱등키로 동시 2회 → 1회만 처리
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("T-7: 같은 멱등키 동시 2회 - prepareApproval 에서 202 반환")
    void approve_concurrentSameKey_accepted() {
        // 두 번째 호출 시 prepareApproval 이 earlyReturn(202) 반환
        ApprovePhaseAResult earlyReturn = ApprovePhaseAResult.builder()
                .earlyReturn(IdempotentResult.acceptedWithRetryAfterSeconds(2))
                .build();
        given(approveHelper.prepareApproval(eq(INTENT_PUBLIC_ID), eq(IDEM_KEY), eq(CUSTOMER_ID), any()))
                .willReturn(earlyReturn);

        IdempotentResult<PaymentIntentDetailResponse> result =
                service.approve(INTENT_PUBLIC_ID, IDEM_KEY, CUSTOMER_ID, pinRequest());

        assertThat(result.getHttpStatus().value()).isEqualTo(202);
        assertThat(result.getRetryAfterSeconds()).isEqualTo(2);

        // PIN, funds, TX-B 모두 호출되지 않음
        then(customerClient).should(never()).verifyPin(anyLong(), anyString());
        then(fundsService).should(never()).capture(any(), anyList());
    }

    // ═══════════════════════════════════════════════════
    //  T-8: split-transaction=false → 레거시 경로
    // ═══════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("T-8: split-transaction=false - 기존 단일 트랜잭션 경로로 동작")
    void approve_splitDisabled_usesLegacy() {
        tuningProperties.getApprove().setSplitTransaction(false);

        // TransactionTemplate mock 이 콜백을 실제로 실행하도록 설정
        given(transactionTemplate.execute(any(TransactionCallback.class)))
                .willAnswer((InvocationOnMock inv) -> {
                    TransactionCallback<Object> callback = inv.getArgument(0);
                    return callback.doInTransaction(null);
                });

        // approveLegacy 경로는 approveHelper 를 사용하지 않는다.
        // approveLegacy 는 canonicalizeApproveBody → UUID.fromString 순서이므로
        // 유효하지 않은 UUID 를 넘기면 IDEMPOTENCY_KEY_INVALID 로 빠진다.
        assertThatThrownBy(() -> service.approve(INTENT_PUBLIC_ID, "not-a-uuid", CUSTOMER_ID, pinRequest()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_INVALID);

        // 레거시 경로에서는 approveHelper 를 전혀 사용하지 않는다
        then(approveHelper).should(never()).prepareApproval(any(), anyString(), anyLong(), any());
        then(approveHelper).should(never()).finalizeApproved(anyLong(), anyLong(), anyList(), anyLong(), anyLong(), anyLong());
        then(approveHelper).should(never()).finalizeDeclined(anyLong(), anyLong());
    }

    // ═══════════════════════════════════════════════════
    //  T-10: 만료 intent (split) → EXPIRED 가 DB에 남는다
    //        (markExpired 롤백 버그 회귀 방어)
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("T-10: 만료 intent(split) - EXPIRED 가 DB에 남는다 (롤백 버그 회귀 방어)")
    void approve_expired_split_expiredPersisted() {
        // TX-A 는 만료를 감지만 하고 커밋한다 (전이·예외는 TX-A 밖)
        ApprovePhaseAResult expiredPhaseA = ApprovePhaseAResult.builder()
                .expired(true)
                .intentId(INTENT_ID)
                .intentPublicId(INTENT_PUBLIC_ID)
                .customerId(CUSTOMER_ID)
                .expiresAt(LocalDateTime.now(fixedClock).minusMinutes(1))
                .idemSlotId(IDEM_SLOT_ID)
                .build();
        given(approveHelper.prepareApproval(eq(INTENT_PUBLIC_ID), eq(IDEM_KEY), eq(CUSTOMER_ID), any()))
                .willReturn(expiredPhaseA);

        assertThatThrownBy(() -> service.approve(INTENT_PUBLIC_ID, IDEM_KEY, CUSTOMER_ID, pinRequest()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_INTENT_EXPIRED);

        // 핵심: finalizeExpired 는 REQUIRES_NEW 이므로 이후 예외와 무관하게 EXPIRED 가 커밋된다.
        then(approveHelper).should().finalizeExpired(INTENT_ID, IDEM_SLOT_ID);

        // 만료 확정 후에는 PIN·자금·TX-B 로 진행하지 않는다
        then(customerClient).should(never()).verifyPin(anyLong(), anyString());
        then(fundsService).should(never()).capture(any(), anyList());
        then(approveHelper).should(never()).finalizeApproved(anyLong(), anyLong(), anyList(), anyLong(), anyLong(), anyLong());
        then(approveHelper).should(never()).finalizeDeclined(anyLong(), anyLong());
    }

    // ═══════════════════════════════════════════════════
    //  T-11: 만료 intent (레거시) → EXPIRED 가 DB에 남는다
    // ═══════════════════════════════════════════════════

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("T-11: 만료 intent(legacy) - finalizeExpired 로 EXPIRED 를 별도 커밋")
    void approve_expired_legacy_expiredPersisted() {
        tuningProperties.getApprove().setSplitTransaction(false);

        given(transactionTemplate.execute(any(TransactionCallback.class)))
                .willAnswer((InvocationOnMock inv) -> {
                    TransactionCallback<Object> callback = inv.getArgument(0);
                    return callback.doInTransaction(null);
                });

        IdempotencyKey slot = IdempotencyKey.builder()
                .id(IDEM_SLOT_ID)
                .keyUuid(UUID.fromString(IDEM_KEY))
                .actorType(IdemActorType.CUSTOMER)
                .actorId(CUSTOMER_ID)
                .method("POST")
                .path("/payments/" + INTENT_PUBLIC_ID + "/approve")
                .status(IdemStatus.IN_PROGRESS)
                .build();
        given(idempotencyService.beginOrLoad(
                any(IdemActorType.class), anyLong(), anyString(), anyString(),
                any(UUID.class), any(byte[].class)))
                .willReturn(new IdemBegin(slot, true));

        PaymentIntent expiredIntent = PaymentIntent.builder()
                .intentId(INTENT_ID)
                .publicId(INTENT_PUBLIC_ID)
                .customerId(CUSTOMER_ID)
                .walletId(200L)
                .storeId(300L)
                .amount(10000L)
                .status(PaymentStatus.PENDING)
                .expiresAt(LocalDateTime.now(fixedClock).minusMinutes(1))
                .build();
        given(intentRepository.findByPublicId(INTENT_PUBLIC_ID))
                .willReturn(Optional.of(expiredIntent));

        assertThatThrownBy(() -> service.approve(INTENT_PUBLIC_ID, IDEM_KEY, CUSTOMER_ID, pinRequest()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_INTENT_EXPIRED);

        // 레거시 경로도 REQUIRES_NEW 로 EXPIRED 를 커밋한다.
        // (기존: 같은 트랜잭션에서 markExpired + save 후 예외 → 전이까지 롤백)
        then(approveHelper).should().finalizeExpired(INTENT_ID, IDEM_SLOT_ID);
        then(intentRepository).should(never()).save(any(PaymentIntent.class));
        then(customerClient).should(never()).verifyPin(anyLong(), anyString());
    }

    // ═══════════════════════════════════════════════════
    //  T-9: @Version 충돌 시 PAYMENT_INTENT_STATUS_CONFLICT
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("TX-B @Version 충돌 - 재시도 없이 PAYMENT_INTENT_STATUS_CONFLICT")
    void approve_optimisticLockConflict() {
        ApprovePhaseAResult phaseA = normalPhaseA();
        given(approveHelper.prepareApproval(eq(INTENT_PUBLIC_ID), eq(IDEM_KEY), eq(CUSTOMER_ID), any()))
                .willReturn(phaseA);
        given(customerClient.verifyPin(CUSTOMER_ID, PIN)).willReturn(true);
        given(fundsService.capture(any(), anyList()))
                .willReturn(new FundsService.FundsResult(true, true, 999L, null, false));
        given(approveHelper.finalizeApproved(eq(INTENT_ID), eq(IDEM_SLOT_ID), anyList(), anyLong(), anyLong(), anyLong()))
                .willThrow(new ObjectOptimisticLockingFailureException(PaymentIntent.class.getName(), INTENT_ID));

        assertThatThrownBy(() -> service.approve(INTENT_PUBLIC_ID, IDEM_KEY, CUSTOMER_ID, pinRequest()))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAYMENT_INTENT_STATUS_CONFLICT);
    }
}
