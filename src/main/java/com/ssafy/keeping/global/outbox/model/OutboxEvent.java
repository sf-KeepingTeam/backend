package com.ssafy.keeping.global.outbox.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Outbox 이벤트 엔티티
 * 도메인 이벤트를 안전하게 발행하기 위한 Transactional Outbox 패턴
 *
 * 동작 방식:
 * 1. 비즈니스 트랜잭션 내에서 OutboxEvent를 생성/저장
 * 2. 트랜잭션 커밋 후 스케줄러가 PENDING 상태의 이벤트를 조회
 * 3. 이벤트를 처리(핸들러 호출)하고 PUBLISHED로 상태 변경
 * 4. 실패 시 retry_count 증가, 최대 재시도 횟수 초과 시 FAILED
 */
@Entity
@Table(
    name = "outbox_events",
    indexes = {
        @Index(name = "idx_outbox_status_created", columnList = "status, createdAt"),
        @Index(name = "idx_outbox_aggregate", columnList = "aggregateType, aggregateId")
    }
)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 집합체 유형 (예: "Customer", "Group", "Wallet")
     */
    @Column(nullable = false, length = 100)
    private String aggregateType;

    /**
     * 집합체 ID (예: customerId, groupId)
     */
    @Column(nullable = false, length = 100)
    private String aggregateId;

    /**
     * 이벤트 유형 (예: "CustomerCreated", "GroupDisbandInitiated")
     */
    @Column(nullable = false, length = 100)
    private String eventType;

    /**
     * 이벤트 페이로드 (JSON 형태)
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String payload;

    /**
     * 이벤트 상태
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private OutboxStatus status = OutboxStatus.PENDING;

    /**
     * 생성 시각
     */
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 발행 시각
     */
    private LocalDateTime publishedAt;

    /**
     * 재시도 횟수
     */
    @Column(nullable = false)
    @Builder.Default
    private int retryCount = 0;

    /**
     * 마지막 에러 메시지
     */
    @Column(length = 1000)
    private String lastErrorMessage;

    /**
     * 발행 완료 처리
     */
    public void markAsPublished() {
        this.status = OutboxStatus.PUBLISHED;
        this.publishedAt = LocalDateTime.now();
    }

    /**
     * 발행 실패 처리
     */
    public void markAsFailed(String errorMessage) {
        this.retryCount++;
        this.lastErrorMessage = errorMessage;
        if (this.retryCount >= 5) { // 최대 5회 재시도
            this.status = OutboxStatus.FAILED;
        }
    }

    /**
     * 재시도 가능 여부
     */
    public boolean canRetry() {
        return this.status == OutboxStatus.PENDING && this.retryCount < 5;
    }
}
