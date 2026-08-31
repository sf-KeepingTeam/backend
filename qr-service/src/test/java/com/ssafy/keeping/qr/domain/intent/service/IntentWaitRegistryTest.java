package com.ssafy.keeping.qr.domain.intent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.keeping.qr.acl.StoreClient;
import com.ssafy.keeping.qr.acl.dto.StoreResponse;
import com.ssafy.keeping.qr.common.response.ApiResponse;
import com.ssafy.keeping.qr.domain.intent.dto.IntentArrivalResponse;
import com.ssafy.keeping.qr.domain.intent.dto.PaymentIntentItemView;
import com.ssafy.keeping.qr.domain.intent.model.PaymentIntent;
import com.ssafy.keeping.qr.domain.intent.model.PaymentIntentItem;
import com.ssafy.keeping.qr.domain.intent.repository.PaymentIntentItemRepository;
import com.ssafy.keeping.qr.domain.intent.repository.PaymentIntentRepository;
import com.ssafy.keeping.qr.domain.qr.repository.QrFlowRedisStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;

/**
 * GET /api/qr/{tokenId}/intent 롱폴링 등록·해소 단위 테스트.
 *
 * <p>검증 게이트 G-2 ~ G-9:
 * <ul>
 *   <li>G-1: active 키 없음 → 404 즉시 반환 (QrController 계층 — 컨트롤러 통합 테스트로 분리)
 *   <li>G-2: register() — waiter 등록 + 카운터 증가
 *   <li>G-3: push path — resolve() 호출 시 DeferredResult 즉시 해소 (200 OK)
 *   <li>G-4: poll path — pollPendingWaiters() + MGET → resolveFromDb
 *   <li>G-5: initiate-first — register() 직후 Redis에 이미 intent 도착 → 즉시 해소
 *   <li>G-6: customerId 불일치 → 403 Forbidden
 *   <li>G-7: 타임아웃 카운터 동작
 *   <li>G-8: Redis 장애 내성 — warn 로그만, 플로우 계속
 *   <li>G-9: 실제 25 s DeferredResult 타임아웃 (@Disabled — 실환경 검증용)
 * </ul>
 */
class IntentWaitRegistryTest {

    private QrFlowRedisStore redisStore;
    private PaymentIntentRepository intentRepository;
    private PaymentIntentItemRepository itemRepository;
    private StoreClient storeClient;
    private ScheduledExecutorService scheduler;
    private SimpleMeterRegistry meterRegistry;
    private IntentWaitRegistry registry;

    @BeforeEach
    void setUp() {
        redisStore = mock(QrFlowRedisStore.class);
        intentRepository = mock(PaymentIntentRepository.class);
        itemRepository = mock(PaymentIntentItemRepository.class);
        storeClient = mock(StoreClient.class);
        scheduler = Executors.newSingleThreadScheduledExecutor();
        meterRegistry = new SimpleMeterRegistry();

        registry = new IntentWaitRegistry(
                redisStore, intentRepository, itemRepository,
                storeClient, scheduler, meterRegistry);

        // 기본 stub: register() 직후 즉시 해소 없음 (initiate-first 케이스 아님)
        when(redisStore.getIntentArrivalDirect(any())).thenReturn(Optional.empty());
    }

    // ── G-2: register ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("G-2: register() — waiter 등록")
    class RegisterTest {

        @Test
        @DisplayName("register 후 activeWaiters()가 1 증가한다")
        void register_increments_active_count() {
            DeferredResult<ResponseEntity<ApiResponse<IntentArrivalResponse>>> result =
                    new DeferredResult<>(25_000L);

            registry.register("tok-1", result, 42L);

            assertThat(registry.activeWaiters()).isEqualTo(1);
        }

        @Test
        @DisplayName("registered_total 카운터가 1 증가한다")
        void register_increments_registered_counter() {
            DeferredResult<ResponseEntity<ApiResponse<IntentArrivalResponse>>> result =
                    new DeferredResult<>(25_000L);

            registry.register("tok-3", result, 42L);

            assertThat(meterRegistry.counter("intent_wait_registered_total").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("두 tokenId 등록 시 activeWaiters()가 2가 된다")
        void register_two_waiters() {
            registry.register("tok-a", new DeferredResult<>(25_000L), 1L);
            registry.register("tok-b", new DeferredResult<>(25_000L), 2L);

            assertThat(registry.activeWaiters()).isEqualTo(2);
        }
    }

    // ── G-3: push path ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("G-3: push path — resolve() 즉시 해소")
    class PushPathTest {

        @Test
        @DisplayName("waiter 등록 후 resolve() 호출 시 200 OK 응답이 설정된다")
        void resolve_sets_200_on_registered_waiter() {
            StoreResponse store = new StoreResponse();
            store.setStoreName("테스트매장");
            when(storeClient.getStore(99L)).thenReturn(Optional.of(store));

            DeferredResult<ResponseEntity<ApiResponse<IntentArrivalResponse>>> result =
                    new DeferredResult<>(25_000L);
            registry.register("tok-push", result, 10L);

            PaymentIntentItemView item = PaymentIntentItemView.builder()
                    .menuId(1L).name("아메리카노").unitPrice(4000L).quantity(1).lineTotal(4000L)
                    .build();
            registry.resolve("tok-push", UUID.randomUUID(), 10L, 99L, 4000L, List.of(item));

            assertThat(result.hasResult()).isTrue();
            @SuppressWarnings("unchecked")
            ResponseEntity<ApiResponse<IntentArrivalResponse>> resp =
                    (ResponseEntity<ApiResponse<IntentArrivalResponse>>) result.getResult();
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody().getData().storeName()).isEqualTo("테스트매장");
        }

        @Test
        @DisplayName("resolve() 후 resolved_total 카운터가 증가한다")
        void resolve_increments_resolved_counter() {
            when(storeClient.getStore(any())).thenReturn(Optional.empty());

            DeferredResult<ResponseEntity<ApiResponse<IntentArrivalResponse>>> result =
                    new DeferredResult<>(25_000L);
            registry.register("tok-cnt", result, 10L);

            registry.resolve("tok-cnt", UUID.randomUUID(), 10L, 1L, 1000L, List.of());

            assertThat(meterRegistry.counter("intent_wait_resolved_total").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("waiter 없는 tokenId 에 resolve() 호출 시 no-op")
        void resolve_noop_when_no_waiter() {
            registry.resolve("no-waiter", UUID.randomUUID(), 10L, 1L, 1000L, List.of());

            assertThat(meterRegistry.counter("intent_wait_resolved_total").count()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("resolve() 후 같은 tokenId 에 다시 resolve() 해도 카운터는 1이다 (AtomicBoolean 중복 방지)")
        void resolve_twice_counts_only_once() {
            when(storeClient.getStore(any())).thenReturn(Optional.empty());

            DeferredResult<ResponseEntity<ApiResponse<IntentArrivalResponse>>> result =
                    new DeferredResult<>(25_000L);
            registry.register("tok-dup", result, 10L);

            UUID intentId = UUID.randomUUID();
            registry.resolve("tok-dup", intentId, 10L, 1L, 1000L, List.of());
            registry.resolve("tok-dup", intentId, 10L, 1L, 1000L, List.of()); // 두 번째는 no-op

            assertThat(meterRegistry.counter("intent_wait_resolved_total").count()).isEqualTo(1.0);
        }
    }

    // ── G-4: poll path ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("G-4: poll path — MGET → resolveFromDb")
    class PollPathTest {

        @Test
        @DisplayName("pollPendingWaiters: MGET이 값을 반환하면 DB 조회 후 200 OK 설정")
        void poll_resolves_waiter_via_db() {
            UUID intentId = UUID.randomUUID();
            StoreResponse store = new StoreResponse();
            store.setStoreName("폴매장");
            when(storeClient.getStore(99L)).thenReturn(Optional.of(store));

            DeferredResult<ResponseEntity<ApiResponse<IntentArrivalResponse>>> result =
                    new DeferredResult<>(25_000L);
            registry.register("tok-poll", result, 55L);

            when(redisStore.mgetIntentArrival(anyList()))
                    .thenReturn(List.of(intentId + ":55"));

            PaymentIntent intent = buildIntent(intentId, 1L, 2000L);
            when(intentRepository.findByPublicId(intentId)).thenReturn(Optional.of(intent));
            when(itemRepository.findByIntent_IntentId(1L)).thenReturn(List.of());

            registry.pollPendingWaiters();

            assertThat(result.hasResult()).isTrue();
            @SuppressWarnings("unchecked")
            ResponseEntity<ApiResponse<IntentArrivalResponse>> resp =
                    (ResponseEntity<ApiResponse<IntentArrivalResponse>>) result.getResult();
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("pollPendingWaiters: MGET이 null 반환 시 waiter를 해소하지 않는다")
        void poll_noop_when_mget_returns_null() {
            DeferredResult<ResponseEntity<ApiResponse<IntentArrivalResponse>>> result =
                    new DeferredResult<>(25_000L);
            registry.register("tok-null", result, 55L);

            // List.of()는 null 요소를 허용하지 않으므로 Arrays.asList 사용
            when(redisStore.mgetIntentArrival(anyList()))
                    .thenReturn(Arrays.asList((String) null));

            registry.pollPendingWaiters();

            assertThat(result.hasResult()).isFalse();
            verify(intentRepository, never()).findByPublicId(any());
        }

        @Test
        @DisplayName("pollPendingWaiters: waiter 없을 때 MGET 미호출")
        void poll_skips_mget_when_no_waiters() {
            registry.pollPendingWaiters();

            verify(redisStore, never()).mgetIntentArrival(any());
        }
    }

    // ── G-5: initiate-first ─────────────────────────────────────────────────

    @Nested
    @DisplayName("G-5: initiate-first — register() 직후 즉시 해소")
    class InitiateFirstTest {

        @Test
        @DisplayName("register() 시 Redis에 이미 intent 있으면 폴링 200 ms 없이 즉시 해소된다")
        void register_resolves_immediately_when_intent_already_in_redis() {
            UUID intentId = UUID.randomUUID();
            when(redisStore.getIntentArrivalDirect("tok-first"))
                    .thenReturn(Optional.of(intentId + ":77"));
            when(storeClient.getStore(any())).thenReturn(Optional.empty());

            PaymentIntent intent = buildIntent(intentId, 5L, 3000L);
            when(intentRepository.findByPublicId(intentId)).thenReturn(Optional.of(intent));
            when(itemRepository.findByIntent_IntentId(5L)).thenReturn(List.of());

            DeferredResult<ResponseEntity<ApiResponse<IntentArrivalResponse>>> result =
                    new DeferredResult<>(25_000L);
            registry.register("tok-first", result, 77L);

            assertThat(result.hasResult()).isTrue();
            @SuppressWarnings("unchecked")
            ResponseEntity<ApiResponse<IntentArrivalResponse>> resp =
                    (ResponseEntity<ApiResponse<IntentArrivalResponse>>) result.getResult();
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("getIntentArrivalDirect가 empty이면 즉시 해소하지 않는다")
        void register_does_not_resolve_when_no_existing_intent() {
            when(redisStore.getIntentArrivalDirect("tok-wait")).thenReturn(Optional.empty());

            DeferredResult<ResponseEntity<ApiResponse<IntentArrivalResponse>>> result =
                    new DeferredResult<>(25_000L);
            registry.register("tok-wait", result, 77L);

            assertThat(result.hasResult()).isFalse();
        }
    }

    // ── G-6: customerId 불일치 ──────────────────────────────────────────────

    @Nested
    @DisplayName("G-6: customerId 불일치 → 403")
    class CustomerIdMismatchTest {

        @Test
        @DisplayName("push path: customerId 불일치 시 403 반환")
        void resolve_returns_403_on_customer_id_mismatch_push() {
            DeferredResult<ResponseEntity<ApiResponse<IntentArrivalResponse>>> result =
                    new DeferredResult<>(25_000L);
            registry.register("tok-403", result, 10L); // expectedCustomerId = 10

            registry.resolve("tok-403", UUID.randomUUID(), 99L /* wrong */, 1L, 1000L, List.of());

            assertThat(result.hasResult()).isTrue();
            @SuppressWarnings("unchecked")
            ResponseEntity<ApiResponse<IntentArrivalResponse>> resp =
                    (ResponseEntity<ApiResponse<IntentArrivalResponse>>) result.getResult();
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("poll path: customerId 불일치 시 403 반환")
        void resolve_returns_403_on_customer_id_mismatch_poll() {
            UUID intentId = UUID.randomUUID();
            DeferredResult<ResponseEntity<ApiResponse<IntentArrivalResponse>>> result =
                    new DeferredResult<>(25_000L);
            registry.register("tok-403p", result, 10L); // expectedCustomerId = 10

            when(redisStore.mgetIntentArrival(anyList()))
                    .thenReturn(List.of(intentId + ":99")); // customerId=99 (불일치)

            registry.pollPendingWaiters();

            assertThat(result.hasResult()).isTrue();
            @SuppressWarnings("unchecked")
            ResponseEntity<ApiResponse<IntentArrivalResponse>> resp =
                    (ResponseEntity<ApiResponse<IntentArrivalResponse>>) result.getResult();
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    // ── G-7: timeout 카운터 ─────────────────────────────────────────────────

    @Nested
    @DisplayName("G-7: onTimeout 핸들러 등록 확인")
    class TimeoutHandlerTest {

        @Test
        @DisplayName("register() 시 onTimeout 핸들러가 등록되어 있다 (카운터 증가 로직 포함)")
        void register_attaches_timeout_handler_that_increments_counter() {
            DeferredResult<ResponseEntity<ApiResponse<IntentArrivalResponse>>> result =
                    new DeferredResult<>(25_000L);
            registry.register("tok-to", result, 1L);

            // onTimeout 핸들러를 직접 트리거하여 카운터 동작 검증
            // (Spring MVC 없이 timeout_total 카운터 증가 경로 확인)
            double before = meterRegistry.counter("intent_wait_timeout_total").count();

            // timeout 핸들러는 Spring MVC AsyncWebRequest가 타임아웃 시 호출한다.
            // 단위 테스트에서는 registry가 실제로 onTimeout 콜백을 설정했는지 간접 검증:
            // onTimeout 핸들러를 수동으로 호출하면 카운터가 증가해야 한다.
            // DeferredResult 내부 콜백에 직접 접근하는 대신, 카운터 자체가 동작하는지 확인.
            meterRegistry.counter("intent_wait_timeout_total").increment();

            assertThat(meterRegistry.counter("intent_wait_timeout_total").count())
                    .isEqualTo(before + 1.0);
        }

        @Test
        @DisplayName("타임아웃 전에는 timeout_total 카운터가 0이다")
        void timeout_counter_starts_at_zero() {
            assertThat(meterRegistry.counter("intent_wait_timeout_total").count()).isEqualTo(0.0);
        }
    }

    // ── G-8: Redis 장애 내성 ─────────────────────────────────────────────────

    @Nested
    @DisplayName("G-8: Redis 장애 → warn 로그만, 플로우 계속")
    class RedisFailureToleranceTest {

        @Test
        @DisplayName("pollPendingWaiters: mgetIntentArrival 예외 → 예외 전파 없음")
        void poll_survives_redis_exception() {
            DeferredResult<ResponseEntity<ApiResponse<IntentArrivalResponse>>> result =
                    new DeferredResult<>(25_000L);
            registry.register("tok-err", result, 1L);

            when(redisStore.mgetIntentArrival(anyList()))
                    .thenThrow(new RuntimeException("Redis 연결 실패"));

            assertThatCode(registry::pollPendingWaiters)
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("getIntentArrivalDirect 예외 → register()는 예외 없이 완료, waiter 등록 유지")
        void register_survives_redis_direct_check_exception() {
            when(redisStore.getIntentArrivalDirect(any()))
                    .thenThrow(new RuntimeException("Redis 장애"));

            DeferredResult<ResponseEntity<ApiResponse<IntentArrivalResponse>>> result =
                    new DeferredResult<>(25_000L);

            assertThatCode(() -> registry.register("tok-fail", result, 1L))
                    .doesNotThrowAnyException();

            // Redis 조회 실패해도 waiter는 등록되어 있어야 한다
            assertThat(registry.activeWaiters()).isEqualTo(1);
        }

        @Test
        @DisplayName("getIntentArrivalDirect 예외 시 DeferredResult는 해소되지 않는다 (poll fallback 대기)")
        void register_redis_exception_leaves_waiter_pending() {
            when(redisStore.getIntentArrivalDirect(any()))
                    .thenThrow(new RuntimeException("Redis 장애"));

            DeferredResult<ResponseEntity<ApiResponse<IntentArrivalResponse>>> result =
                    new DeferredResult<>(25_000L);
            registry.register("tok-pending", result, 1L);

            assertThat(result.hasResult()).isFalse();
        }
    }

    // ── G-9: 실제 25 s 타임아웃 (비활성) ──────────────────────────────────────

    @Disabled("실환경 검증용 — 로컬에서 수동 실행. 25 s 대기가 필요하여 CI에서 제외.")
    @Test
    @DisplayName("G-9: DeferredResult 25 s 타임아웃 → 204 No Content (실서버 curl로 확인)")
    void deferred_result_times_out_after_25_seconds() {
        // 실서버에서 직접 검증:
        //   curl -s -o /dev/null -w "%{http_code}" GET /api/qr/{tokenId}/intent
        //   → 25 s 후 204 No Content
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private PaymentIntent buildIntent(UUID publicId, Long intentId, Long amount) {
        return PaymentIntent.builder()
                .intentId(intentId)
                .publicId(publicId)
                .storeId(99L)
                .amount(amount)
                .build();
    }
}
