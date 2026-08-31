package com.ssafy.keeping.qr.domain.intent.service;

import com.ssafy.keeping.qr.acl.StoreClient;
import com.ssafy.keeping.qr.common.response.ApiResponse;
import com.ssafy.keeping.qr.domain.intent.dto.IntentArrivalResponse;
import com.ssafy.keeping.qr.domain.intent.dto.PaymentIntentItemView;
import com.ssafy.keeping.qr.domain.intent.model.PaymentIntent;
import com.ssafy.keeping.qr.domain.intent.model.PaymentIntentItem;
import com.ssafy.keeping.qr.domain.intent.repository.PaymentIntentItemRepository;
import com.ssafy.keeping.qr.domain.intent.repository.PaymentIntentRepository;
import com.ssafy.keeping.qr.domain.qr.repository.QrFlowRedisStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.async.DeferredResult;

/**
 * GET /api/qr/{tokenId}/intent 롱폴링 대기자 관리.
 *
 * <p>두 가지 해소 경로:
 * <ol>
 *   <li><b>push</b> — {@link QrFlowIntentReadyListener} 가 AFTER_COMMIT 직후 {@link #resolve} 호출.
 *       waiter 가 등록되어 있으면 즉시 해소 (폴링 200 ms 기다릴 필요 없음).
 *   <li><b>poll</b> — {@code intentWaitScheduler} 가 200 ms 주기로 Redis MGET. push 가 누락된
 *       경우(waiter 가 push 이전에 등록되지 않은 엣지케이스) 를 처리하는 safety-net.
 * </ol>
 *
 * <p>동시성: {@link ConcurrentHashMap} 으로 waiter 관리.
 * {@link DeferredResult#setResult} 는 AtomicBoolean 으로 중복 호출 안전.
 */
@Slf4j
@Component
public class IntentWaitRegistry {

    private record WaiterEntry(
            DeferredResult<ResponseEntity<ApiResponse<IntentArrivalResponse>>> result,
            Long expectedCustomerId) {}

    private final ConcurrentHashMap<String, WaiterEntry> waiters = new ConcurrentHashMap<>();

    private final QrFlowRedisStore qrFlowRedisStore;
    private final PaymentIntentRepository intentRepository;
    private final PaymentIntentItemRepository itemRepository;
    private final StoreClient storeClient;
    private final ScheduledExecutorService scheduler;

    // ── 지표 ─────────────────────────────────────────────────────────
    private final Counter waitRegisteredCounter;
    private final Counter waitResolvedCounter;
    private final Counter waitTimeoutCounter;
    private final AtomicInteger activeWaiterCount = new AtomicInteger(0);

    public IntentWaitRegistry(
            QrFlowRedisStore qrFlowRedisStore,
            PaymentIntentRepository intentRepository,
            PaymentIntentItemRepository itemRepository,
            StoreClient storeClient,
            @Qualifier("intentWaitScheduler") ScheduledExecutorService scheduler,
            MeterRegistry meterRegistry) {
        this.qrFlowRedisStore = qrFlowRedisStore;
        this.intentRepository = intentRepository;
        this.itemRepository = itemRepository;
        this.storeClient = storeClient;
        this.scheduler = scheduler;
        this.waitRegisteredCounter = meterRegistry.counter("intent_wait_registered_total");
        this.waitResolvedCounter   = meterRegistry.counter("intent_wait_resolved_total");
        this.waitTimeoutCounter    = meterRegistry.counter("intent_wait_timeout_total");
        meterRegistry.gauge("intent_wait_active", activeWaiterCount);
    }

    @PostConstruct
    void startPoller() {
        scheduler.scheduleAtFixedRate(this::pollPendingWaiters, 200, 200, TimeUnit.MILLISECONDS);
        log.info("[INTENT_WAIT] 폴러 시작 — 주기 200 ms");
    }

    // ── 공개 API ──────────────────────────────────────────────────────

    /**
     * 롱폴링 DeferredResult 를 등록한다.
     *
     * <p>onCompletion 핸들러로 맵 자동 정리 + 게이지 감소.
     * onTimeout 핸들러로 타임아웃 카운터 증가.
     */
    public void register(
            String tokenId,
            DeferredResult<ResponseEntity<ApiResponse<IntentArrivalResponse>>> result,
            Long expectedCustomerId) {
        result.onCompletion(() -> {
            waiters.remove(tokenId);
            activeWaiterCount.decrementAndGet();
        });
        result.onTimeout(() -> waitTimeoutCounter.increment());

        waiters.put(tokenId, new WaiterEntry(result, expectedCustomerId));
        activeWaiterCount.incrementAndGet();
        waitRegisteredCounter.increment();
        log.debug("[INTENT_WAIT] 등록 — tokenId={} customerId={}", tokenId, expectedCustomerId);

        // initiate 가 waiter 등록보다 먼저 완료된 경우 즉시 해소 (폴링 200 ms 생략)
        try {
            qrFlowRedisStore.getIntentArrivalDirect(tokenId).ifPresent(value -> {
                String[] parts = value.split(":", 2);
                if (parts.length == 2) {
                    try {
                        UUID intentPublicId = UUID.fromString(parts[0]);
                        Long customerId     = Long.parseLong(parts[1]);
                        log.debug("[INTENT_WAIT] 등록 직후 즉시 해소 — tokenId={}", tokenId);
                        resolveFromDb(tokenId, intentPublicId, customerId);
                    } catch (Exception e) {
                        log.warn("[INTENT_WAIT] 즉시 해소 파싱 실패 — tokenId={} error={}",
                                tokenId, e.getMessage());
                    }
                }
            });
        } catch (Exception e) {
            log.warn("[INTENT_WAIT] 즉시 해소 Redis 조회 실패 — tokenId={} error={}",
                    tokenId, e.getMessage());
        }
    }

    /**
     * push path: 리스너에서 직접 호출. 이벤트가 items 를 포함하므로 DB 조회 불필요.
     *
     * <p>waiter 가 없으면 no-op (손님이 아직 폴링을 시작하지 않은 경우 — 폴링 경로가 처리).
     */
    public void resolve(
            String tokenId,
            UUID intentPublicId,
            Long customerId,
            Long storeId,
            Long amount,
            List<PaymentIntentItemView> items) {
        WaiterEntry entry = waiters.get(tokenId);
        if (entry == null) {
            return; // 아직 손님이 폴링 안 함 — poll path 가 Redis 에서 처리
        }
        if (!customerId.equals(entry.expectedCustomerId())) {
            entry.result().setResult(ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("접근 권한이 없습니다.", 403)));
            waiters.remove(tokenId);
            return;
        }
        String storeName = fetchStoreName(storeId);
        IntentArrivalResponse response =
                new IntentArrivalResponse(intentPublicId.toString(), amount, storeName, items);
        boolean set = entry.result().setResult(
                ResponseEntity.ok(ApiResponse.success("결제 요청 도착", 200, response)));
        if (set) {
            waitResolvedCounter.increment();
            log.info("[INTENT_WAIT] 해소(push) — tokenId={} intentPublicId={}", tokenId, intentPublicId);
        }
    }

    // ── 폴링 (200 ms 주기) ────────────────────────────────────────────

    void pollPendingWaiters() {
        try {
            List<String> tokenIds = new ArrayList<>(waiters.keySet());
            if (tokenIds.isEmpty()) return;

            List<String> values = qrFlowRedisStore.mgetIntentArrival(tokenIds);

            for (int i = 0; i < tokenIds.size(); i++) {
                String value = values.get(i);
                if (value == null) continue;
                String tokenId = tokenIds.get(i);
                String[] parts = value.split(":", 2);
                if (parts.length != 2) continue;
                try {
                    UUID intentPublicId = UUID.fromString(parts[0]);
                    Long customerId     = Long.parseLong(parts[1]);
                    resolveFromDb(tokenId, intentPublicId, customerId);
                } catch (Exception e) {
                    log.warn("[INTENT_WAIT] poll 파싱 실패 — tokenId={} value={} error={}",
                            tokenId, value, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[INTENT_WAIT] poll 오류 — {}", e.getMessage());
        }
    }

    /** poll path: DB + StoreClient 로 전체 응답 구성. */
    private void resolveFromDb(String tokenId, UUID intentPublicId, Long customerId) {
        WaiterEntry entry = waiters.get(tokenId);
        if (entry == null) return;

        if (!customerId.equals(entry.expectedCustomerId())) {
            entry.result().setResult(ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("접근 권한이 없습니다.", 403)));
            waiters.remove(tokenId);
            return;
        }
        try {
            PaymentIntent intent = intentRepository.findByPublicId(intentPublicId).orElse(null);
            if (intent == null) {
                log.warn("[INTENT_WAIT] poll: intent not found — intentPublicId={}", intentPublicId);
                return;
            }
            List<PaymentIntentItem> rows = itemRepository.findByIntent_IntentId(intent.getIntentId());
            List<PaymentIntentItemView> itemViews = rows.stream()
                    .map(this::toItemView)
                    .collect(Collectors.toList());
            String storeName = fetchStoreName(intent.getStoreId());

            IntentArrivalResponse response = new IntentArrivalResponse(
                    intentPublicId.toString(), intent.getAmount(), storeName, itemViews);
            boolean set = entry.result().setResult(
                    ResponseEntity.ok(ApiResponse.success("결제 요청 도착", 200, response)));
            if (set) {
                waitResolvedCounter.increment();
                log.info("[INTENT_WAIT] 해소(poll) — tokenId={} intentPublicId={}", tokenId, intentPublicId);
            }
        } catch (Exception e) {
            log.warn("[INTENT_WAIT] resolveFromDb 실패 — tokenId={} error={}", tokenId, e.getMessage());
        }
    }

    // ── 내부 유틸 ─────────────────────────────────────────────────────

    private String fetchStoreName(Long storeId) {
        try {
            return storeClient.getStore(storeId)
                    .map(s -> s.getStoreName())
                    .orElse("매장");
        } catch (Exception e) {
            log.warn("[INTENT_WAIT] StoreClient 실패 — storeId={} fallback='매장'", storeId);
            return "매장";
        }
    }

    private PaymentIntentItemView toItemView(PaymentIntentItem it) {
        long line = (it.getLineTotal() != null)
                ? it.getLineTotal()
                : it.getUnitPriceSnap() * it.getQuantity();
        return PaymentIntentItemView.builder()
                .menuId(it.getMenuId())
                .name(it.getMenuNameSnap())
                .unitPrice(it.getUnitPriceSnap())
                .quantity(it.getQuantity())
                .lineTotal(line)
                .build();
    }

    /** 테스트용 — 현재 waiter 수 반환. */
    int activeWaiters() {
        return waiters.size();
    }
}
