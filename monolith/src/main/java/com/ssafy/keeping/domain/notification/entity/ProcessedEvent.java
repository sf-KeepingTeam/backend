package com.ssafy.keeping.domain.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 알림 이벤트 멱등성 테이블.
 *
 * <p>이벤트 1건이 알림 2건(점주+고객)을 생성하므로, notifications 테이블에 event_id UNIQUE 를 붙일 수 없다.
 * 별도 테이블이 맞다.
 *
 * <p>{@code idx_processed_at} 은 나중에 정리 배치를 붙일 때 사용한다.
 */
@Entity
@Table(
    name = "processed_event",
    indexes = {@Index(name = "idx_processed_at", columnList = "processed_at")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class ProcessedEvent {

  @Id
  @Column(name = "event_id", length = 36, nullable = false)
  private String eventId;

  @Column(name = "event_type", length = 50, nullable = false)
  private String eventType;

  @Column(name = "processed_at", columnDefinition = "DATETIME(3)", nullable = false)
  private LocalDateTime processedAt;
}
