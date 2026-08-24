package com.ssafy.keeping.qr.domain.intent.outbox;

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

  /** 정리 배치용 — SENT 이후 retention 기간이 지난 행 삭제. 트랜잭션은 호출 서비스가 연다. */
  @Modifying
  @Query(
      "DELETE FROM PaymentOutbox o WHERE o.status = :status AND o.sentAt < :cutoff")
  int deleteByStatusAndSentAtBefore(
      @Param("status") OutboxStatus status, @Param("cutoff") LocalDateTime cutoff);
}
