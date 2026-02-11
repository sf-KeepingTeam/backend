package com.ssafy.keeping.qr.domain.intent.model;

import com.ssafy.keeping.qr.common.exception.CustomException;
import com.ssafy.keeping.qr.common.exception.ErrorCode;
import com.ssafy.keeping.qr.domain.intent.constant.PaymentStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "payment_intent",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_intent_qr_token", columnNames = {"qr_token_id"}),
                @UniqueConstraint(name = "uk_intent_public_id", columnNames = {"public_id"}),
                @UniqueConstraint(name = "uk_intent_idem", columnNames = {"idempotency_key"})
        },
        indexes = {
                @Index(name = "idx_status_expires", columnList = "status,expires_at"),
                @Index(name = "idx_store_status", columnList = "store_id,status"),
                @Index(name = "idx_wallet_status", columnList = "wallet_id,status")
        }
)
public class PaymentIntent {
    // 낙관적 락 : 동시성 안전성(concurrency control)
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "intent_id")
    private Long intentId;

    @Column(name = "public_id", nullable = false, unique = true, columnDefinition = "BINARY(16)")
    private UUID publicId;

    @Column(name = "qr_token_id", nullable = false, unique = true)
    private String qrTokenId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentStatus status;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime updatedAt;

    @Column(name = "expires_at", nullable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime expiresAt;

    @Column(name = "approved_at", columnDefinition = "DATETIME(3)")
    private LocalDateTime approvedAt;

    @Column(name = "declined_at", columnDefinition = "DATETIME(3)")
    private LocalDateTime declinedAt;

    @Column(name = "canceled_at", columnDefinition = "DATETIME(3)")
    private LocalDateTime canceledAt;

    @Column(name = "completed_at", columnDefinition = "DATETIME(3)")
    private LocalDateTime completedAt;

    @Column(name = "idempotency_key", length = 64, unique = true)
    private String idempotencyKey;

    @PrePersist
    public void onCreate() {
        if (publicId == null) publicId = UUID.randomUUID();
        if (status == null) status = PaymentStatus.PENDING;
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = createdAt;
    }

    @PreUpdate
    public void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public void markApproved(LocalDateTime now) {
        // 만료 검사
        if (now.isAfter(this.expiresAt)) {
            throw new CustomException(ErrorCode.PAYMENT_INTENT_EXPIRED);
        }
        // 전이 가능 상태만 허용
        if (this.status != PaymentStatus.PENDING) {
            throw new CustomException(ErrorCode.PAYMENT_STATUS_CONFLICT);
        }
        this.status = PaymentStatus.APPROVED;
        this.approvedAt = now;
    }

    public void markDeclined(LocalDateTime now) {
        if (this.status != PaymentStatus.PENDING) {
            throw new CustomException(ErrorCode.PAYMENT_STATUS_CONFLICT);
        }
        this.status = PaymentStatus.DECLINED;
        this.declinedAt = now;
    }

    public void markCanceled(LocalDateTime now) {
        if (this.status != PaymentStatus.PENDING) {
            throw new CustomException(ErrorCode.PAYMENT_STATUS_CONFLICT);
        }
        this.status = PaymentStatus.CANCELED;
        this.canceledAt = now;
    }

    public void markExpired(LocalDateTime now) {
        if (this.status != PaymentStatus.PENDING) {
            throw new CustomException(ErrorCode.PAYMENT_STATUS_CONFLICT);
        }
        this.status = PaymentStatus.EXPIRED;
    }
}
