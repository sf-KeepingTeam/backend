package com.ssafy.keeping.domain.charge.model;

import com.ssafy.keeping.domain.payment.transactions.model.Transaction;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "settlement_tasks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class SettlementTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "task_id")
    private Long taskId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    @Enumerated(EnumType.STRING)
    @Column(name = "Status", nullable = false)
    private Status status;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "actual_payment_amount", nullable = false)
    private Long actualPaymentAmount;

    public enum Status {
        PENDING,    // 대기 중 (3일 후 처리 예정)
        COMPLETED,  // 정산 완료
        CANCELED,   // 취소됨 (결제 취소로 인해)
        FAILED,      // 정산 실패
        LOCKED // 청구서 발행한 뒤 상태
    }

    // 정산 완료 처리
    public void markAsCompleted() {
        this.status = Status.COMPLETED;
        this.processedAt = LocalDateTime.now();
    }

    // 정산 취소 처리
    public void markAsCanceled() {
        this.status = Status.CANCELED;
        this.processedAt = LocalDateTime.now();
    }

    // 정산 실패 처리
    public void markAsFailed() {
        this.status = Status.FAILED;
        this.processedAt = LocalDateTime.now();
    }

    // 정산 잠금 처리 (결제취소 불가)
    public void markAsLocked() {
        this.status = Status.LOCKED;
        this.processedAt = LocalDateTime.now();
    }

    // 처리 가능 시간 계산 (생성 시간 + 3일)
    public LocalDateTime getScheduledProcessTime() {
        return this.createdAt.plusDays(3);
    }

    // 처리 시간이 되었는지 확인
    public boolean isReadyForProcessing() {
        return LocalDateTime.now().isAfter(getScheduledProcessTime()) && 
               this.status == Status.PENDING;
    }
}