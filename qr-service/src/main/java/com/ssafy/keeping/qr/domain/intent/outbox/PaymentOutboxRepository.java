package com.ssafy.keeping.qr.domain.intent.outbox;

import jakarta.transaction.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentOutboxRepository extends JpaRepository<PaymentOutbox, Long> {

  /** 폴러가 PENDING 행을 id 순으로 읽는다. batch-size 는 Pageable 로 제어. */
  List<PaymentOutbox> findByStatusOrderByIdAsc(OutboxStatus status, Pageable pageable);

  /** Prometheus Gauge 용 — 특정 상태의 행 수. */
  long countByStatus(OutboxStatus status);

  /** 정리 배치용 — SENT 이후 retention 기간이 지난 행 삭제. */
  @Transactional
  @Modifying
  @Query("DELETE FROM PaymentOutbox o WHERE o.status = :status AND o.sentAt < :cutoff")
  int deleteByStatusAndSentAtBefore(
      @Param("status") OutboxStatus status, @Param("cutoff") LocalDateTime cutoff);

  /**
   * 발행 성공 전이 — 프록시를 통해 각자 짧은 트랜잭션으로 커밋. 엔티티 로드 없이 UPDATE 단건.
   *
   * <p>Bug A 수정: OutboxPublisher 의 self-invocation(@Transactional 무효화) 대체.
   */
  @Transactional
  @Modifying
  @Query(
      "UPDATE PaymentOutbox o"
          + " SET o.status = com.ssafy.keeping.qr.domain.intent.outbox.OutboxStatus.SENT,"
          + "     o.sentAt = :sentAt"
          + " WHERE o.id = :id")
  int markSent(@Param("id") Long id, @Param("sentAt") LocalDateTime sentAt);

  /** 재시도 카운트 증가 — status 는 PENDING 유지. */
  @Transactional
  @Modifying
  @Query(
      "UPDATE PaymentOutbox o"
          + " SET o.retryCount = o.retryCount + 1, o.lastError = :error"
          + " WHERE o.id = :id")
  int incrementRetry(@Param("id") Long id, @Param("error") String error);

  /** 최종 실패 전이. */
  @Transactional
  @Modifying
  @Query(
      "UPDATE PaymentOutbox o"
          + " SET o.status = com.ssafy.keeping.qr.domain.intent.outbox.OutboxStatus.FAILED,"
          + "     o.lastError = :error"
          + " WHERE o.id = :id")
  int markFailed(@Param("id") Long id, @Param("error") String error);

  /**
   * SENT + 기한 초과 행을 batchSize 건씩 끊어 삭제. MySQL DELETE...LIMIT 을 사용해 락 범위를 제한.
   * JPQL 은 DELETE LIMIT 을 지원하지 않으므로 nativeQuery.
   */
  @Transactional
  @Modifying
  @Query(
      value =
          "DELETE FROM payment_outbox"
              + " WHERE status = 'SENT' AND sent_at < :cutoff"
              + " ORDER BY id LIMIT :batchSize",
      nativeQuery = true)
  int deleteOldSentBatch(
      @Param("cutoff") LocalDateTime cutoff, @Param("batchSize") int batchSize);
}
