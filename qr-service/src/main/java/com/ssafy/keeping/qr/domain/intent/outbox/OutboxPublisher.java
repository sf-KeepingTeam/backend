package com.ssafy.keeping.qr.domain.intent.outbox;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ssafy.keeping.qr.config.PaymentTuningProperties;

/**
 * 아웃박스 폴러 + 정리 배치.
 *
 * <h3>트랜잭션 경계</h3>
 * <ol>
 *   <li>[짧은 TX] PENDING N건 조회 → 스칼라(id, partitionKey, payload, retryCount) 복사</li>
 *   <li>[TX 밖] 한 건씩 {@code KafkaTemplate.send().get(5s)} — 네트워크 대기</li>
 *   <li>[짧은 TX] 건별 결과에 따라 status 갱신</li>
 * </ol>
 *
 * <p>엔티티를 트랜잭션 밖으로 들고 나가지 않는다 — 스칼라 DTO({@link PendingEntry})만 반환.
 *
 * <p>동기({@code .get()}) 선택 근거: 알림은 초 단위 지연을 허용하며 목표는 유실 방지다.
 * 콜백은 오류 처리 흐름이 복잡해지고 순서 보장이 어려워진다. 동기 + 짧은 타임아웃(5초)이면
 * 한 사이클 최악 = batchSize * 5초이고, fixedDelay 라 겹치지 않는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "payment.outbox.enabled", havingValue = "true")
public class OutboxPublisher {

    /** Kafka 토픽. PaymentTuningProperties 를 건드리면 안 되므로 상수로 둔다. */
    static final String TOPIC = "keeping.payment.notification.v1";

    private static final int MAX_ERROR_LENGTH = 500;
    private static final int CLEANUP_BATCH_SIZE = 1_000;
    private static final long SEND_TIMEOUT_SECONDS = 5L;

    private final PaymentOutboxRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final PaymentTuningProperties tuning;
    private final OutboxMetrics metrics;

    public OutboxPublisher(
            PaymentOutboxRepository repository,
            KafkaTemplate<String, String> kafkaTemplate,
            PaymentTuningProperties tuning,
            OutboxMetrics metrics) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.tuning = tuning;
        this.metrics = metrics;
    }

    // ── 폴러 ────────────────────────────────────────────────────────────

    /**
     * fixedDelay 사용 — fixedRate 가 아니다. fixedRate 는 이전 실행이 안 끝나도 다음이 뜬다.
     *
     * <p>트랜잭션 경계: repository 의 각 @Modifying 메서드가 자체 트랜잭션을 열고 닫는다.
     * Kafka send 는 트랜잭션 밖(네트워크 대기)에서 수행된다.
     * 이전에 fetchPending/markSent/markRetry/markFailed 에 @Transactional 을 달았으나
     * 같은 빈의 this.* 호출은 Spring AOP 프록시를 우회하므로 트랜잭션이 열리지 않았다 — Bug A.
     */
    @Scheduled(fixedDelayString = "${payment.outbox.poll-interval-ms:500}")
    public void publishPending() {
        int batchSize = tuning.getOutbox().getBatchSize();
        List<PendingEntry> entries = repository
                .findByStatusOrderByIdAsc(OutboxStatus.PENDING, PageRequest.of(0, batchSize))
                .stream()
                .map(o -> new PendingEntry(o.getId(), o.getEventId(), o.getPartitionKey(),
                        o.getPayload(), o.getRetryCount()))
                .toList();

        if (entries.isEmpty()) {
            metrics.updatePendingCount(repository.countByStatus(OutboxStatus.PENDING));
            return;
        }

        for (PendingEntry entry : entries) {
            try {
                kafkaTemplate.send(TOPIC, entry.partitionKey, entry.payload)
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                repository.markSent(entry.id, LocalDateTime.now());
                metrics.publishSuccess();
                log.info("[OUTBOX] published id={} eventId={} key={}", entry.id, entry.eventId, entry.partitionKey);
            } catch (ExecutionException | TimeoutException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                handleFailure(entry, e);
            }
        }

        // Gauge 갱신 — 발행 후 남은 PENDING 수 반영
        metrics.updatePendingCount(repository.countByStatus(OutboxStatus.PENDING));
    }

    // ── 정리 배치 ───────────────────────────────────────────────────────

    /**
     * 매일 새벽 4시, SENT 상태이고 보관 기한이 지난 행을 1000건씩 끊어서 삭제한다.
     * FAILED 는 조사 대상이므로 삭제하지 않는다.
     */
    @Scheduled(cron = "0 0 4 * * *")
    public void cleanup() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(tuning.getOutbox().getRetentionDays());
        long totalDeleted = 0;

        int deleted;
        do {
            deleted = repository.deleteOldSentBatch(cutoff, CLEANUP_BATCH_SIZE);
            totalDeleted += deleted;
        } while (deleted >= CLEANUP_BATCH_SIZE);

        log.info("[OUTBOX] cleanup deleted={} olderThan={}", totalDeleted, cutoff);
    }

    // ── 내부 메서드 ─────────────────────────────────────────────────────

    /**
     * 발행 실패 시 retry 또는 FAILED 처리. 한 건의 실패가 폴러 전체를 죽이지 않는다.
     */
    private void handleFailure(PendingEntry entry, Exception e) {
        String error = truncate(e.getMessage(), MAX_ERROR_LENGTH);
        int maxRetry = tuning.getOutbox().getMaxRetry();

        if (entry.retryCount + 1 > maxRetry) {
            repository.markFailed(entry.id, error);
            metrics.failed();
            log.error("[OUTBOX] FAILED id={} eventId={} count={} error={}", entry.id, entry.eventId,
                    entry.retryCount + 1, error);
        } else {
            repository.incrementRetry(entry.id, error);
            metrics.publishFailure();
            log.warn("[OUTBOX] retry id={} eventId={} count={}/{} error={}", entry.id, entry.eventId,
                    entry.retryCount + 1, maxRetry, error);
        }
    }

    // ── 스칼라 DTO ──────────────────────────────────────────────────────

    /** 트랜잭션 밖으로 반출하는 스칼라 값 컨테이너. 엔티티를 들고 나가지 않는다. */
    record PendingEntry(Long id, String eventId, String partitionKey, String payload, int retryCount) {}

    // ── 유틸 ────────────────────────────────────────────────────────────

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
