package com.ssafy.keeping.qr.saga.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.ssafy.keeping.qr.saga.constant.SagaEventType;
import com.ssafy.keeping.qr.saga.constant.SagaStatus;
import com.ssafy.keeping.qr.saga.constant.TargetService;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "saga_log", indexes = {
        @Index(name = "idx_saga_status_retry", columnList = "status, next_retry_at"),
        @Index(name = "idx_saga_aggregate", columnList = "aggregate_id")
})
public class SagaLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;  // PaymentIntent.publicId

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50)
    private SagaEventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_service", nullable = false, length = 50)
    private TargetService targetService;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SagaStatus status = SagaStatus.PENDING;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSON")
    private JsonNode payload;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private Integer retryCount = 0;

    @Column(name = "max_retries", nullable = false)
    @Builder.Default
    private Integer maxRetries = 3;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", columnDefinition = "DATETIME(3)")
    private LocalDateTime updatedAt;

    @Column(name = "completed_at", columnDefinition = "DATETIME(3)")
    private LocalDateTime completedAt;

    @PrePersist
    public void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = SagaStatus.PENDING;
        }
        if (retryCount == null) {
            retryCount = 0;
        }
        if (maxRetries == null) {
            maxRetries = 3;
        }
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * 재시도 가능 여부
     */
    public boolean canRetry() {
        return retryCount < maxRetries;
    }

    /**
     * 재시도 횟수 증가
     */
    public void incrementRetry() {
        this.retryCount++;
    }

    /**
     * 완료 처리
     */
    public void markCompleted() {
        this.status = SagaStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * 실패 처리
     */
    public void markFailed(String errorMessage) {
        this.status = SagaStatus.FAILED;
        this.errorMessage = errorMessage;
    }

    /**
     * 처리중으로 변경
     */
    public void markProcessing() {
        this.status = SagaStatus.PROCESSING;
    }

    /**
     * 재시도 예약
     */
    public void scheduleRetry(LocalDateTime nextRetryAt, String errorMessage) {
        this.status = SagaStatus.PENDING;
        this.nextRetryAt = nextRetryAt;
        this.errorMessage = errorMessage;
        incrementRetry();
    }
}
