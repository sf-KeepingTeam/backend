package com.ssafy.keeping.global.metrics;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 지갑 원장(Wallet-Ledger) 관련 Prometheus 메트릭.
 *
 * <p>Gauge는 약한 참조로 등록되므로, AtomicLong을 빈 필드로 강하게 보관한다.
 */
@Component
public class LedgerMetrics {

  // ---- Metric name constants ----
  public static final String MISMATCH_TOTAL = "wallet_ledger_mismatch_total";
  public static final String CAUSE_LOT_SHORTFALL = "lot_shortfall";
  public static final String CAUSE_EXPIRY_CLAMP = "expiry_clamp";
  public static final String CAUSE_RECONCILE = "reconcile";

  // ---- Gauge backing fields (strong references) ----
  private final AtomicLong expiryBacklogGauge = new AtomicLong(0);
  private final AtomicLong reconcileMismatchPairsGauge = new AtomicLong(0);
  private final AtomicLong reconcileLastRunGauge = new AtomicLong(0);

  private final MeterRegistry registry;

  public LedgerMetrics(MeterRegistry registry) {
    this.registry = registry;

    // Gauge 등록 — 강한 참조 필드를 사용
    Gauge.builder("wallet_lot_expiry_backlog", expiryBacklogGauge, Number::doubleValue)
        .description("Number of wallet-groups with lots pending expiry processing")
        .register(registry);

    Gauge.builder("wallet_reconcile_mismatch_pairs", reconcileMismatchPairsGauge, Number::doubleValue)
        .description("Number of wallet-store pairs with balance vs lot-sum mismatch from last reconcile run")
        .register(registry);

    Gauge.builder("wallet_reconcile_last_run_epoch_seconds", reconcileLastRunGauge, Number::doubleValue)
        .description("Epoch seconds of the last reconcile run completion")
        .register(registry);
  }

  // ---- Counter helpers ----

  /** 잔액표와 로트 합계 불일치 발견 시. */
  public void mismatch(String cause) {
    Counter.builder("wallet_ledger_mismatch_total")
        .tag("cause", cause)
        .description("Total wallet-ledger mismatches detected")
        .register(registry)
        .increment();
  }

  /** 만료 로트 처리 완료 시. lots = 삭제된 로트 수, amount = 소멸 금액 합. */
  public void lotExpired(long lots, long amount) {
    Counter.builder("wallet_lot_expired_total")
        .description("Total number of expired lots removed")
        .register(registry)
        .increment(lots);

    Counter.builder("wallet_lot_expired_amount_total")
        .description("Total monetary amount of expired lots")
        .register(registry)
        .increment(amount);
  }

  /** 만료 처리 대상(백로그) 갱신. */
  public void expiryBacklog(long remaining) {
    expiryBacklogGauge.set(remaining);
  }

  /** 만료 처리 중 락 획득 실패로 스킵된 지갑-그룹 수. */
  public void expirySkippedLocked(long n) {
    Counter.builder("wallet_lot_expiry_skipped_locked_total")
        .description("Total wallet-groups skipped during expiry due to lock contention")
        .register(registry)
        .increment(n);
  }

  /** 대사(reconcile) 결과 기록. */
  public void reconcileResult(long mismatchPairs, Instant finishedAt) {
    reconcileMismatchPairsGauge.set(mismatchPairs);
    reconcileLastRunGauge.set(finishedAt.getEpochSecond());
  }
}
