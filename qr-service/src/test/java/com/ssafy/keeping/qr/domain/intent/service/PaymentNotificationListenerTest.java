package com.ssafy.keeping.qr.domain.intent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.qr.acl.CustomerClient;
import com.ssafy.keeping.qr.acl.MenuClient;
import com.ssafy.keeping.qr.acl.NotificationClient;
import com.ssafy.keeping.qr.acl.StoreClient;
import com.ssafy.keeping.qr.acl.dto.StoreResponse;
import com.ssafy.keeping.qr.config.PaymentTuningProperties;
import com.ssafy.keeping.qr.domain.idempotency.constant.IdemStatus;
import com.ssafy.keeping.qr.domain.idempotency.dto.IdemBegin;
import com.ssafy.keeping.qr.domain.idempotency.model.IdempotencyKey;
import com.ssafy.keeping.qr.domain.idempotency.service.IdempotencyService;
import com.ssafy.keeping.qr.domain.intent.constant.PaymentStatus;
import com.ssafy.keeping.qr.domain.intent.dto.ApproveRequest;
import com.ssafy.keeping.qr.domain.intent.event.PaymentApprovedEvent;
import com.ssafy.keeping.qr.domain.intent.event.PaymentRequestedEvent;
import com.ssafy.keeping.qr.domain.intent.model.PaymentIntent;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 세트 #30 — 알림 비동기화 테스트 (작성만, 실행 금지).
 *
 * <p>N-1 ~ N-5 시나리오.
 */
@ExtendWith(MockitoExtension.class)
class PaymentNotificationListenerTest {

    @Mock private NotificationClient notificationClient;
    @Mock private StoreClient storeClient;

    @InjectMocks private PaymentNotificationListener listener;

    private StoreResponse dummyStore;

    @BeforeEach
    void setUp() {
        dummyStore = new StoreResponse();
        dummyStore.setStoreId(10L);
        dummyStore.setStoreName("테스트매장");
        dummyStore.setOwnerId(99L);
    }

    // ───────────────────────────────────────────────
    // N-1: approve 성공 → 커밋 이후에 알림 리스너가 호출됨
    // ───────────────────────────────────────────────
    @Test
    @DisplayName("N-1: onPaymentApproved 가 호출되면 점주+고객 알림을 전송한다")
    void n1_approveSuccess_sendsNotifications() {
        // given
        given(storeClient.getStore(10L)).willReturn(Optional.of(dummyStore));
        PaymentApprovedEvent event = new PaymentApprovedEvent(1L, 5L, 10L, 15000L);

        // when
        listener.onPaymentApproved(event);

        // then — 점주 알림 1건 + 고객 알림 1건
        then(notificationClient).should().sendToOwner(eq(99L), eq("PAYMENT_APPROVED"), anyString());
        then(notificationClient)
                .should()
                .sendToCustomer(eq(5L), eq("PAYMENT_APPROVED"), anyString());
    }

    // ───────────────────────────────────────────────
    // N-2: 알림 리스너에서 예외 발생 → 결제는 성립, 예외 전파 안 됨
    // ───────────────────────────────────────────────
    @Test
    @DisplayName("N-2: 리스너 내부 예외가 밖으로 전파되지 않는다 (결제 안전)")
    void n2_listenerException_doesNotPropagate() {
        // given — storeClient 가 예외를 던지도록 설정
        given(storeClient.getStore(anyLong()))
                .willThrow(new RuntimeException("monolith 연결 실패"));
        PaymentApprovedEvent event = new PaymentApprovedEvent(1L, 5L, 10L, 15000L);

        // when & then — 예외가 밖으로 나오지 않아야 한다
        assertThatCode(() -> listener.onPaymentApproved(event)).doesNotThrowAnyException();

        // notificationClient 는 호출되지 않아야 한다 (storeClient 에서 먼저 터졌으므로)
        then(notificationClient).shouldHaveNoInteractions();
    }

    // ───────────────────────────────────────────────
    // N-3: async=false → 기존 동기 경로로 호출됨
    //      (PaymentIntentService 레벨에서 분기 검증)
    // ───────────────────────────────────────────────
    @Test
    @DisplayName("N-3: async=false 시 이벤트 발행 없이 notificationClient 를 직접 호출한다")
    void n3_asyncFalse_syncPathUsed() throws Exception {
        // given — async=false 로 설정
        PaymentTuningProperties syncProps = new PaymentTuningProperties();
        syncProps.getNotification().setAsync(false);

        // 목 의존성 준비
        PaymentIntentRepository mockIntentRepo = mock(PaymentIntentRepository.class);
        PaymentIntentItemRepository mockItemRepo = mock(PaymentIntentItemRepository.class);
        IdempotencyService mockIdemService = mock(IdempotencyService.class);
        FundsService mockFundsService = mock(FundsService.class);
        QrTokenService mockQrService = mock(QrTokenService.class);
        MenuClient mockMenuClient = mock(MenuClient.class);
        StoreClient mockStoreClient = mock(StoreClient.class);
        CustomerClient mockCustomerClient = mock(CustomerClient.class);
        NotificationClient mockNotifClient = mock(NotificationClient.class);
        ObjectMapper mockMapper = mock(ObjectMapper.class);
        given(mockMapper.writeValueAsString(any())).willReturn("{\"pin\":\"123456\"}");
        ApplicationEventPublisher mockPublisher = mock(ApplicationEventPublisher.class);
        Clock fixedClock = Clock.fixed(Instant.parse("2026-08-23T10:00:00Z"), ZoneId.of("Asia/Seoul"));
        ApproveTransactionHelper mockApproveHelper = mock(ApproveTransactionHelper.class);
        org.springframework.transaction.support.TransactionTemplate mockTxTemplate =
                mock(org.springframework.transaction.support.TransactionTemplate.class);
        PinTokenVerifier mockPinTokenVerifier = mock(PinTokenVerifier.class);

        // async=false → split-transaction=false 로 레거시 경로 강제
        syncProps.getApprove().setSplitTransaction(false);

        // TransactionTemplate mock 이 콜백을 실제로 실행
        given(mockTxTemplate.execute(any(org.springframework.transaction.support.TransactionCallback.class)))
                .willAnswer(inv -> {
                    org.springframework.transaction.support.TransactionCallback<Object> cb = inv.getArgument(0);
                    return cb.doInTransaction(null);
                });

        ObjectMapper mockPrimaryMapper = mock(ObjectMapper.class);
        com.ssafy.keeping.qr.domain.intent.outbox.PaymentOutboxRepository mockOutboxRepo =
                mock(com.ssafy.keeping.qr.domain.intent.outbox.PaymentOutboxRepository.class);

        PaymentIntentService service = new PaymentIntentService(
                mockIntentRepo, mockItemRepo, mockIdemService, mockFundsService,
                mockQrService, mockMenuClient, mockStoreClient, mockCustomerClient,
                mockNotifClient, mockMapper, mockPrimaryMapper, fixedClock, mockPublisher, syncProps,
                mockApproveHelper, mockTxTemplate, mockPinTokenVerifier, mockOutboxRepo,
                mock(QrFlowRedisStore.class));

        // Intent 스텁
        UUID publicId = UUID.randomUUID();
        PaymentIntent intent = PaymentIntent.builder()
                .intentId(1L).publicId(publicId).customerId(5L).storeId(10L)
                .walletId(1L).qrTokenId("tok").amount(15000L)
                .status(PaymentStatus.PENDING)
                .createdAt(LocalDateTime.now(fixedClock).minusMinutes(1))
                .updatedAt(LocalDateTime.now(fixedClock).minusMinutes(1))
                .expiresAt(LocalDateTime.now(fixedClock).plusMinutes(2))
                .version(0L).build();

        given(mockIntentRepo.findByPublicId(publicId)).willReturn(Optional.of(intent));
        given(mockItemRepo.findByIntent_IntentId(1L)).willReturn(List.of());
        given(mockCustomerClient.verifyPin(5L, "123456")).willReturn(true);
        given(mockFundsService.capture(any(), any()))
                .willReturn(new FundsService.FundsResult(true, true, 1L, null, false));
        given(mockStoreClient.getStore(10L)).willReturn(Optional.of(dummyStore));

        // 멱등 — 새 슬롯 선점
        IdempotencyKey slot = mock(IdempotencyKey.class);
        given(slot.getStatus()).willReturn(IdemStatus.IN_PROGRESS);
        IdemBegin begin = new IdemBegin(slot, true);
        given(mockIdemService.beginOrLoad(any(), any(), any(), any(), any(), any()))
                .willReturn(begin);
        given(mockIdemService.isBodyConflict(any(), any())).willReturn(false);

        ApproveRequest req = new ApproveRequest();
        // ApproveRequest has no setter — set via reflection
        Field pinField = ApproveRequest.class.getDeclaredField("pin");
        pinField.setAccessible(true);
        pinField.set(req, "123456");

        String idempotencyKey = UUID.randomUUID().toString();

        // when
        service.approve(publicId, idempotencyKey, 5L, req);

        // then — 이벤트는 발행되지 않고 notificationClient 가 직접 호출됨
        then(mockPublisher).shouldHaveNoInteractions();
        then(mockNotifClient).should().sendToOwner(eq(99L), eq("PAYMENT_APPROVED"), anyString());
        then(mockNotifClient).should().sendToCustomer(eq(5L), eq("PAYMENT_APPROVED"), anyString());
    }

    // ───────────────────────────────────────────────
    // N-4: 이벤트 객체에 엔티티 참조가 없다 (필드 타입 검사)
    // ───────────────────────────────────────────────
    @Test
    @DisplayName("N-4: PaymentApprovedEvent / PaymentRequestedEvent 에 JPA 엔티티 필드가 없다")
    void n4_eventHasNoEntityReferences() {
        // PaymentApprovedEvent 의 모든 필드가 값 타입(Long)인지 검증
        for (Field field : PaymentApprovedEvent.class.getDeclaredFields()) {
            Class<?> fieldType = field.getType();
            assertThat(fieldType.getPackageName())
                    .as("PaymentApprovedEvent.%s 은(는) 엔티티가 아니어야 한다", field.getName())
                    .doesNotStartWith("com.ssafy.keeping.qr.domain");
        }

        // PaymentRequestedEvent 의 모든 필드가 값 타입(Long)인지 검증
        for (Field field : PaymentRequestedEvent.class.getDeclaredFields()) {
            Class<?> fieldType = field.getType();
            assertThat(fieldType.getPackageName())
                    .as("PaymentRequestedEvent.%s 은(는) 엔티티가 아니어야 한다", field.getName())
                    .doesNotStartWith("com.ssafy.keeping.qr.domain");
        }
    }

    // ───────────────────────────────────────────────
    // N-5: 스레드풀 큐 포화 시 결제 스레드가 블로킹되지 않는다
    // ───────────────────────────────────────────────
    @Test
    @DisplayName("N-5: 큐 포화 시 DiscardPolicy 로 버려지고 caller 스레드가 블로킹되지 않는다")
    void n5_queueSaturation_doesNotBlockCaller() throws InterruptedException {
        // given — 극단적으로 작은 풀: core=1, max=1, queue=1
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("test-notif-");

        AtomicBoolean rejected = new AtomicBoolean(false);
        executor.setRejectedExecutionHandler((r, pool) -> {
            rejected.set(true);
            // 작업을 버린다 (DiscardPolicy 동등)
        });
        executor.initialize();

        CountDownLatch blockLatch = new CountDownLatch(1);

        try {
            // 스레드풀의 유일한 스레드를 점유하는 블로킹 작업
            executor.execute(() -> {
                try {
                    blockLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });

            // 큐 슬롯 1개를 채운다
            executor.execute(() -> {});

            // when — 이제 큐도 스레드도 꽉 찼다. 추가 submit 이 caller 를 블로킹하면 안 된다
            long start = System.nanoTime();
            executor.execute(() -> {}); // 이 작업은 버려져야 한다
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            // then — caller 스레드가 100ms 이내에 돌아와야 한다 (블로킹 없음)
            assertThat(elapsedMs)
                    .as("큐 포화 시 caller 스레드가 블로킹되지 않아야 한다")
                    .isLessThan(100);
            assertThat(rejected.get())
                    .as("거부 핸들러가 호출되어야 한다")
                    .isTrue();
        } finally {
            blockLatch.countDown();
            executor.shutdown();
        }
    }
}
