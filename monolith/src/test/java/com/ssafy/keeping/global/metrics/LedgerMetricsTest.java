package com.ssafy.keeping.global.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LedgerMetricsTest {

  private MeterRegistry registry;
  private LedgerMetrics metrics;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    metrics = new LedgerMetrics(registry);
  }

  // ---- 7개 메트릭 등록 확인 ----

  @Test
  @DisplayName("mismatch Counter가 cause 태그와 함께 등록된다")
  void mismatch_counter_registered() {
    metrics.mismatch("lot_shortfall");

    double count =
        registry.get("wallet_ledger_mismatch_total").tag("cause", "lot_shortfall").counter().count();
    assertThat(count).isEqualTo(1.0);
  }

  @Test
  @DisplayName("lotExpired 호출 시 expired_total, expired_amount_total 두 Counter가 등록된다")
  void lotExpired_counters_registered() {
    metrics.lotExpired(3, 15000);

    assertThat(registry.get("wallet_lot_expired_total").counter().count()).isEqualTo(3.0);
    assertThat(registry.get("wallet_lot_expired_amount_total").counter().count()).isEqualTo(15000.0);
  }

  @Test
  @DisplayName("expiryBacklog Gauge가 등록된다")
  void expiryBacklog_gauge_registered() {
    assertThat(registry.get("wallet_lot_expiry_backlog").gauge()).isNotNull();
  }

  @Test
  @DisplayName("expirySkippedLocked Counter가 등록된다")
  void expirySkippedLocked_counter_registered() {
    metrics.expirySkippedLocked(5);

    assertThat(registry.get("wallet_lot_expiry_skipped_locked_total").counter().count())
        .isEqualTo(5.0);
  }

  @Test
  @DisplayName("reconcile Gauge 2개가 등록된다")
  void reconcile_gauges_registered() {
    assertThat(registry.get("wallet_reconcile_mismatch_pairs").gauge()).isNotNull();
    assertThat(registry.get("wallet_reconcile_last_run_epoch_seconds").gauge()).isNotNull();
  }

  // ---- Gauge 값 변경 후 재조회 ----

  @Test
  @DisplayName("expiryBacklog Gauge 값 변경 후 재조회 시 반영된다")
  void expiryBacklog_gauge_value_updates() {
    metrics.expiryBacklog(42);
    assertThat(registry.get("wallet_lot_expiry_backlog").gauge().value()).isEqualTo(42.0);

    metrics.expiryBacklog(0);
    assertThat(registry.get("wallet_lot_expiry_backlog").gauge().value()).isEqualTo(0.0);
  }

  @Test
  @DisplayName("reconcileResult 호출 시 두 Gauge가 올바른 값으로 갱신된다")
  void reconcileResult_updates_both_gauges() {
    Instant now = Instant.parse("2026-08-21T03:40:00Z");
    metrics.reconcileResult(7, now);

    assertThat(registry.get("wallet_reconcile_mismatch_pairs").gauge().value()).isEqualTo(7.0);
    assertThat(registry.get("wallet_reconcile_last_run_epoch_seconds").gauge().value())
        .isEqualTo((double) now.getEpochSecond());
  }

  @Test
  @DisplayName("mismatch Counter는 cause 태그별로 독립 카운트된다")
  void mismatch_counter_independent_per_cause() {
    metrics.mismatch("lot_shortfall");
    metrics.mismatch("lot_shortfall");
    metrics.mismatch("expiry_clamp");

    assertThat(
            registry
                .get("wallet_ledger_mismatch_total")
                .tag("cause", "lot_shortfall")
                .counter()
                .count())
        .isEqualTo(2.0);
    assertThat(
            registry
                .get("wallet_ledger_mismatch_total")
                .tag("cause", "expiry_clamp")
                .counter()
                .count())
        .isEqualTo(1.0);
  }
}
