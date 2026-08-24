package com.ssafy.keeping.qr.domain.intent.outbox;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.*;

/**
 * 아웃박스 패턴용 엔티티. 결제 이벤트를 비즈니스 트랜잭션과 함께 기록하고, 폴러(#52)가 Kafka 로 발행한다.
 *
 * <p>DDL 원본: {@code deploy/seed/V2026_08_24__payment_outbox.sql}. 운영은 {@code ddl-auto=validate}
 * 이므로 인프라가 DDL 을 수동 실행해야 한다. {@code @Table(indexes)} 는 문서 역할이며 validate 는 인덱스를
 * 검사하지 않는다.
 */
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Entity
@Table(
    name = "payment_outbox",
    uniqueConstraints = {
      @UniqueConstraint(name = "uk_outbox_event_id", columnNames = {"event_id"})
    },
    indexes = {
      @Index(name = "idx_outbox_poll", columnList = "status, id"),
      @Index(name = "idx_outbox_cleanup", columnList = "status, sent_at")
    })
public class PaymentOutbox {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "event_id", nullable = false, length = 36)
  private String eventId;

  @Column(name = "aggregate_type", nullable = false, length = 50)
  private String aggregateType;

  @Column(name = "aggregate_id", nullable = false)
  private Long aggregateId;

  @Column(name = "event_type", nullable = false, length = 50)
  private String eventType;

  @Column(name = "partition_key", nullable = false, length = 64)
  private String partitionKey;

  @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
  private String payload;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  @Builder.Default
  private OutboxStatus status = OutboxStatus.PENDING;

  @Column(name = "retry_count", nullable = false)
  @Builder.Default
  private int retryCount = 0;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "sent_at")
  private LocalDateTime sentAt;

  @Column(name = "last_error", length = 500)
  private String lastError;

  @PrePersist
  void prePersist() {
    if (this.createdAt == null) {
      this.createdAt = LocalDateTime.now();
    }
  }

  /** 발행 성공 시 호출. */
  public void markSent(LocalDateTime sentTime) {
    this.status = OutboxStatus.SENT;
    this.sentAt = sentTime;
  }

  /** 발행 실패 시 재시도 카운트를 올린다. status 는 PENDING 을 유지한다. */
  public void markRetry(String error) {
    this.retryCount++;
    this.lastError = error;
  }

  /** 최대 재시도 초과 등으로 최종 실패 처리. */
  public void markFailed(String error) {
    this.status = OutboxStatus.FAILED;
    this.lastError = error;
  }
}
