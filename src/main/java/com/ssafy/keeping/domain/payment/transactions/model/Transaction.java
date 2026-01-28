package com.ssafy.keeping.domain.payment.transactions.model;

import com.ssafy.keeping.domain.payment.transactions.constant.TransactionStatus;
import com.ssafy.keeping.domain.payment.transactions.constant.TransactionType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder(toBuilder = true)
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "transaction_id")
    private Long transactionId;

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    @Column(name = "related_wallet_id")
    private Long relatedWalletId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false)
    private TransactionType transactionType;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "transaction_unique_no", length = 50)
    private String transactionUniqueNo;

    /**
     * 트랜잭션 상태 (선생성 패턴용)
     * null인 경우 COMPLETED로 간주 (하위 호환성)
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    @Builder.Default
    private TransactionStatus status = TransactionStatus.COMPLETED;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ref_tx_id",
            foreignKey = @ForeignKey(name = "fk_tx_ref_tx"))
    private Transaction refTransaction;   // null: 일반거래, not null: CANCEL_*가 참조하는 부모

    /**
     * 트랜잭션 완료 처리
     */
    public void markAsCompleted() {
        this.status = TransactionStatus.COMPLETED;
    }

    /**
     * 트랜잭션 실패 처리
     */
    public void markAsFailed() {
        this.status = TransactionStatus.FAILED;
    }

    /**
     * 완료된 트랜잭션인지 확인
     */
    public boolean isCompleted() {
        return this.status == null || this.status == TransactionStatus.COMPLETED;
    }
}
