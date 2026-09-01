package com.ssafy.keeping.domain.notification.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * 알림 Kafka 컨슈머 Prometheus 메트릭.
 *
 * <p>{@link com.ssafy.keeping.global.metrics.LedgerMetrics} 패턴을 따른다.
 */
@Component
public class NotificationConsumerMetrics {

  private final MeterRegistry registry;
  private final Counter duplicateSkipped;
  private final Counter dltTotal;
  private final Timer lagTimer;

  public NotificationConsumerMetrics(MeterRegistry registry) {
    this.registry = registry;

    this.duplicateSkipped =
        Counter.builder("notification_duplicate_skipped_total")
            .description("Total duplicate notification events skipped")
            .register(registry);

    this.dltTotal =
        Counter.builder("notification_dlt_total")
            .description("Total notification events sent to DLT")
            .register(registry);

    this.lagTimer =
        Timer.builder("notification_lag_seconds")
            .description("Lag between event occurredAt and consumer processing time")
            .register(registry);
  }

  /** 정상 처리 또는 실패. */
  public void consumed(String eventType, String result) {
    Counter.builder("notification_consumed_total")
        .tag("eventType", eventType)
        .tag("result", result)
        .description("Total notification events consumed")
        .register(registry)
        .increment();
  }

  /** 중복 이벤트 스킵. */
  public void duplicateSkipped() {
    duplicateSkipped.increment();
  }

  /** DLT 전송. */
  public void dlt() {
    dltTotal.increment();
  }

  /** occurredAt 에서 지금까지의 지연을 기록한다. 음수이면 0으로 클램프한다. */
  public void recordLag(Duration lag) {
    Duration clamped = lag.isNegative() ? Duration.ZERO : lag;
    lagTimer.record(clamped);
  }
}
