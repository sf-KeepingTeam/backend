package com.ssafy.keeping.domain.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

/**
 * 알림 이벤트 멱등성 테이블.
 *
 * <p>이벤트 1건이 알림 2건(점주+고객)을 생성하므로, notifications 테이블에 event_id UNIQUE 를 붙일 수 없다.
 * 별도 테이블이 맞다.
 *
 * <p>{@code idx_processed_at} 은 나중에 정리 배치를 붙일 때 사용한다.
 *
 * <p><b>Bug B 수정</b>: {@code event_id} 는 수동 할당 {@code @Id} 로 {@code @GeneratedValue} 가 없다.
 * {@code SimpleJpaRepository.save()} 는 {@code isNew()} 가 false 일 때 {@code merge()} 를 호출하므로
 * {@code DataIntegrityViolationException} 이 발생하지 않아 중복 이벤트를 걸러내지 못한다.
 * {@link Persistable} 구현으로 새 엔티티일 때 {@code persist()} 를 강제해 UNIQUE 제약이 작동하도록 한다.
 */
@Entity
@Table(
    name = "processed_event",
    indexes = {@Index(name = "idx_processed_at", columnList = "processed_at")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedEvent implements Persistable<String> {

  @Id
  @Column(name = "event_id", length = 36, nullable = false)
  private String eventId;

  @Column(name = "event_type", length = 50, nullable = false)
  private String eventType;

  @Column(name = "processed_at", columnDefinition = "DATETIME(3)", nullable = false)
  private LocalDateTime processedAt;

  /**
   * 새 엔티티 여부 플래그. 생성 시 true, DB 로드 후 false.
   *
   * <p>{@code @Transient} 이므로 컬럼 매핑 없음. {@code @PostLoad} 가 DB 조회 후 false 로 전환해
   * 이미 저장된 행을 다시 persist 하지 않는다.
   */
  @Transient
  private boolean isNew = true;

  public ProcessedEvent(String eventId, String eventType, LocalDateTime processedAt) {
    this.eventId = eventId;
    this.eventType = eventType;
    this.processedAt = processedAt;
  }

  @PostLoad
  void markNotNew() {
    this.isNew = false;
  }

  @Override
  public String getId() {
    return eventId;
  }

  @Override
  public boolean isNew() {
    return isNew;
  }
}
