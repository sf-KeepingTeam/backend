package com.ssafy.keeping.qr.domain.intent.outbox;

import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 아웃박스 Prometheus 지표 3종.
 *
 * <ul>
 *   <li>{@code payment_outbox_pending} (Gauge) — 현재 PENDING 행 수</li>
 *   <li>{@code payment_outbox_published_total} (Counter) — 발행 시도 결과 {@code result=success|failure}</li>
 *   <li>{@code payment_outbox_failed_total} (Counter) — 최대 재시도 초과로 FAILED 전환된 건수</li>
 * </ul>
 *
 * <p>Gauge 는 {@link AtomicLong} 필드로 강한 참조를 유지한다. Micrometer Gauge 는 약한 참조로
 * 등록되므로 로컬 변수만으로는 GC 후 NaN 이 된다.
 *
 * <p>고카디널리티 라벨(customerId, intentId, storeId 등) 없음.
 */
@Component
@ConditionalOnProperty(name = "payment.outbox.enabled", havingValue = "true")
public class OutboxMetrics {

    private final AtomicLong pendingCount = new AtomicLong(0);

    private final Counter publishedSuccess;
    private final Counter publishedFailure;
    private final Counter failedTotal;

    public OutboxMetrics(MeterRegistry registry) {
        Gauge.builder("payment_outbox_pending", pendingCount, Number::doubleValue)
                .description("Number of PENDING outbox rows awaiting Kafka publication")
                .register(registry);

        publishedSuccess = Counter.builder("payment_outbox_published_total")
                .tag("result", "success")
                .description("Total outbox publish attempts")
                .register(registry);

        publishedFailure = Counter.builder("payment_outbox_published_total")
                .tag("result", "failure")
                .description("Total outbox publish attempts")
                .register(registry);

        failedTotal = Counter.builder("payment_outbox_failed_total")
                .description("Total outbox rows that exceeded max retry and moved to FAILED")
                .register(registry);
    }

    /** 폴러 주기마다 호출 — PENDING 행 수 갱신. */
    public void updatePendingCount(long count) {
        pendingCount.set(count);
    }

    /** 발행 성공. */
    public void publishSuccess() {
        publishedSuccess.increment();
    }

    /** 발행 실패 (재시도 대상 포함). */
    public void publishFailure() {
        publishedFailure.increment();
    }

    /** 최대 재시도 초과로 FAILED 전환. */
    public void failed() {
        failedTotal.increment();
    }
}
