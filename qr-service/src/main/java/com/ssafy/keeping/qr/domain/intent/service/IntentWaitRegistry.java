package com.ssafy.keeping.qr.domain.intent.service;

import com.ssafy.keeping.qr.common.response.ApiResponse;
import com.ssafy.keeping.qr.domain.intent.dto.IntentArrivalCacheValue;
import com.ssafy.keeping.qr.domain.intent.dto.IntentArrivalResponse;
import com.ssafy.keeping.qr.domain.intent.dto.PaymentIntentItemView;
import com.ssafy.keeping.qr.domain.qr.repository.QrFlowRedisStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 *   <li><b>poll</b> — {@code intentWaitScheduler} 가 200 ms 주기로 Redis MGET.
 *       push 가 누락된 엣지케이스(waiter 가 push 이전에 등록되지 않은 경우)를 처리하는 safety-net.
 *       Redis JSON 캐시에서 바로 응답을 구성하므로 DB 왕복이 발생하지 않는다.
 * </ol>
 *
 * <p>동시성: {@link ConcurrentHashMap} 으로 waiter 관리.
 * {@link DeferredResult#setResult} 는 AtomicBoolean 으로 중복 호출 안전.
 *
 * <p>활성화: {@code qr.intent-wait.enabled=true} 일 때만 빈이 등록된다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "qr.intent-wait.enabled", havingValue = "true")
public class IntentWaitRegistry {

    private record WaiterEntry(
            DeferredResult<ResponseEntity<ApiResponse<IntentArrivalResponse>>> result,
            Long expectedCustomerId,
            long startNanos) {}

    private final ConcurrentHashMap<String, WaiterEntry> waiters = new ConcurrentHashMap<>();

    private final QrFlowRedisStore qrFlowRedisStore;
    private final ScheduledExecutorService scheduler;
    private final MeterRegistry meterRegistry;

    // ── 지표 ─────────────────────────────────────────────────────────
    private final Counter waitRegisteredCounter;
    private final Counter waitTimeoutCounter;
    private final AtomicInteger activeWaiterCount = new AtomicInteger(0);

    public IntentWaitRegistry(
            QrFlowRedisStore qrFlowRedisStore,
            @Qualifier("intentWaitScheduler") ScheduledExecutorService scheduler,
            MeterRegistry meterRegistry) {
        this.qrFlowRedisStore = qrFlowRedisStore;
        this.scheduler = scheduler;
        this.meterRegistry = meterRegistry;
        this.waitRegisteredCounter = meterRegistry.counter("intent_wait_registered_total");
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
     * <p>onCompletion 핸들러: 2-arg remove 로 자신이 넣은 엔트리일 때만 맵 정리 + 게이지 감소.
     * (1-arg unconditional remove 는 재시도 시 신규 엔트리를 지워 영구 굶김을 유발한다.)
     */
    public void register(
            String tokenId,
            DeferredResult<ResponseEntity<ApiResponse<IntentArrivalResponse>>> result,
            Long expectedCustomerId) {

        WaiterEntry entry = new WaiterEntry(result, expectedCustomerId, System.nanoTime());

        // 내가 넣은 그 엔트리일 때만 제거 (ConcurrentHashMap 2-arg remove)
        result.onCompletion(() -> {
            if (waiters.remove(tokenId, entry)) {
                activeWaiterCount.decrementAndGet();
            }
        });
        result.onTimeout(() -> waitTimeoutCounter.increment());

        WaiterEntry prev = waiters.put(tokenId, entry);
        if (prev == null) {
            activeWaiterCount.incrementAndGet();
        }
        waitRegisteredCounter.increment();
        log.debug("[INTENT_WAIT] 등록 — tokenId={} customerId={}", tokenId, expectedCustomerId);

        // initiate 가 waiter 등록보다 먼저 완료된 경우 즉시 해소 (폴링 200 ms 생략)
        try {
            qrFlowRedisStore.getIntentArrivalDirect(tokenId).ifPresent(cached -> {
                log.debug("[INTENT_WAIT] 등록 직후 즉시 해소 — tokenId={}", tokenId);
                resolveFromCache(tokenId, cached, "immediate");
            });
        } catch (Exception e) {
            log.warn("[INTENT_WAIT] 즉시 해소 Redis 조회 실패 — tokenId={} error={}",
                    tokenId, e.getMessage());
        }
    }

    /**
     * push path: AFTER_COMMIT 리스너에서 직접 호출.
     *
     * <p>Redis JSON 에 이미 모든 필드가 저장되어 있으므로 DB 조회 불필요.
     * waiter 가 없으면 no-op (손님이 아직 폴링을 시작하지 않은 경우 — poll path 가 처리).
     */
    public void resolve(
            String tokenId,
            UUID intentPublicId,
            Long customerId,
            Long amount,
            String storeName,
            List<PaymentIntentItemView> items) {
        WaiterEntry entry = waiters.get(tokenId);
        if (entry == null) {
            return; // 아직 손님이 폴링 안 함 — poll path 가 Redis 에서 처리
        }
        if (!customerId.equals(entry.expectedCustomerId())) {
            entry.result().setResult(ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("접근 권한이 없습니다.", 403)));
            waiters.remove(tokenId, entry);
            return;
        }
        IntentArrivalResponse response =
                new IntentArrivalResponse(intentPublicId.toString(), amount, storeName, items);
        boolean set = entry.result().setResult(
                ResponseEntity.ok(ApiResponse.success("결제 요청 도착", 200, response)));
        if (set) {
            recordResolved("push", entry.startNanos());
            log.info("[INTENT_WAIT] 해소(push) — tokenId={} intentPublicId={}", tokenId, intentPublicId);
        }
    }

    // ── 폴링 (200 ms 주기) ────────────────────────────────────────────

    void pollPendingWaiters() {
        try {
            List<String> tokenIds = new ArrayList<>(waiters.keySet());
            if (tokenIds.isEmpty()) return;

            List<IntentArrivalCacheValue> values = qrFlowRedisStore.mgetIntentArrival(tokenIds);

            for (int i = 0; i < tokenIds.size(); i++) {
                IntentArrivalCacheValue cached = values.get(i);
                if (cached == null) continue;
                resolveFromCache(tokenIds.get(i), cached, "poll");
            }
        } catch (Exception e) {
            log.warn("[INTENT_WAIT] poll 오류 — {}", e.getMessage());
        }
    }

    /**
     * poll / immediate path: Redis JSON 캐시에서 바로 응답을 구성한다.
     *
     * <p>DB 왕복 수: 수정 전 2, 수정 후 0.
     */
    private void resolveFromCache(String tokenId, IntentArrivalCacheValue cached, String path) {
        WaiterEntry entry = waiters.get(tokenId);
        if (entry == null) return;

        if (!cached.customerId().equals(entry.expectedCustomerId())) {
            entry.result().setResult(ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error("접근 권한이 없습니다.", 403)));
            waiters.remove(tokenId, entry);
            return;
        }
        try {
            IntentArrivalResponse response = new IntentArrivalResponse(
                    cached.intentPublicId(), cached.amount(), cached.storeName(),
                    cached.toItemViews());
            boolean set = entry.result().setResult(
                    ResponseEntity.ok(ApiResponse.success("결제 요청 도착", 200, response)));
            if (set) {
                recordResolved(path, entry.startNanos());
                log.info("[INTENT_WAIT] 해소({}) — tokenId={} intentPublicId={}",
                        path, tokenId, cached.intentPublicId());
            }
        } catch (Exception e) {
            log.warn("[INTENT_WAIT] resolveFromCache 실패 — tokenId={} error={}", tokenId, e.getMessage());
        }
    }

    // ── 내부 지표 ─────────────────────────────────────────────────────

    private void recordResolved(String path, long startNanos) {
        meterRegistry.counter("intent_wait_resolved_total", "path", path).increment();
        long elapsedNanos = System.nanoTime() - startNanos;
        Timer.builder("intent_wait_seconds")
                .tag("path", path)
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
    }

    /** 테스트용 — 현재 waiter 수 반환. */
    int activeWaiters() {
        return waiters.size();
    }
}
