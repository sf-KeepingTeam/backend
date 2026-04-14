package com.ssafy.keeping.domain.charge.model;

import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.store.model.Store;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 결제 예약 엔티티
 * 토스 결제 전 금액을 서버에서 먼저 확정하여 변조 방지
 */
@Entity
@Table(
    name = "payment_reservations",
    indexes = {
        @Index(name = "idx_order_id", columnList = "orderId", unique = true),
//        @Index(name = "idx_customer_status", columnList = "customerId, status"),
        @Index(name = "idx_expires_at", columnList = "expiresAt")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long reservationId;

    /**
     * 주문 ID (토스페이먼츠에 전달할 고유 ID)
     */
    @Column(nullable = false, unique = true, length = 100)
    private String orderId;

    /**
     * 고객
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customerId", nullable = false)
    private Customer customer;

    /**
     * 가게
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "storeId", nullable = false)
    private Store store;

    /**
     * 예약 금액 (서버에서 확정한 금액 - 변조 방지용)
     */
    @Column(nullable = false)
    private Long amount;

    /**
     * 주문명
     */
    @Column(nullable = false, length = 200)
    private String orderName;

    /**
     * 예약 상태
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    /**
     * 만료 시간 (예약 후 10분)
     */
    @Column(nullable = false)
    private LocalDateTime expiresAt;

    /**
     * 토스 paymentKey (결제 완료 후 저장)
     */
    @Column(length = 200)
    private String paymentKey;

    /**
     * 생성 시각
     */
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 수정 시각
     */
    @UpdateTimestamp
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /**
     * 완료 시각
     */
    private LocalDateTime completedAt;

    /**
     * 예약 상태 Enum
     */
    public enum ReservationStatus {
        PENDING,    // 대기 중 (토스 결제 진행 중)
        COMPLETED,  // 완료 (결제 승인 완료)
        EXPIRED,    // 만료 (10분 초과)
        FAILED      // 실패 (토스 결제 실패)
    }

    /**
     * 만료 여부 확인
     */
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }

    /**
     * 완료 처리
     */
    public void markAsCompleted(String paymentKey) {
        this.status = ReservationStatus.COMPLETED;
        this.paymentKey = paymentKey;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * 만료 처리
     */
    public void markAsExpired() {
        this.status = ReservationStatus.EXPIRED;
    }

    /**
     * 실패 처리
     */
    public void markAsFailed() {
        this.status = ReservationStatus.FAILED;
    }
}
