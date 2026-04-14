package com.ssafy.keeping.domain.wallet.model;

import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.payment.transactions.model.Transaction;
import com.ssafy.keeping.domain.wallet.constant.LotSourceType;
import com.ssafy.keeping.domain.wallet.constant.LotStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name="wallet_store_lot",
        indexes = {
                @Index(name="idx_lot_wallet_store", columnList="wallet_id,store_id"),
                @Index(name="idx_lot_origin_tx", columnList="origin_charge_tx_id")
        }
)

@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class WalletStoreLot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "lot_id")
    private Long lotId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(name = "amount_total", nullable = false)
    private Long amountTotal;

    @Column(name = "amount_remaining", nullable = false)
    private Long amountRemaining;

    @Column(name = "acquired_at", nullable = false)
    private LocalDateTime acquiredAt;

    @Column(name = "expired_at", nullable = false)
    private LocalDateTime expiredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private LotSourceType sourceType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contributor_wallet_id")
    private Wallet contributorWallet;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "origin_charge_tx_id", nullable = false)
    private Transaction originChargeTransaction;

    @Enumerated(EnumType.STRING)
    @Column(name = "lot_status", nullable = false, length = 20)
    private LotStatus lotStatus;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancel_tx_id")
    private Transaction cancelTransaction;

    // 포인트 사용 메서드
    public void usePoints(Long amount) {
        if (this.amountRemaining.compareTo(amount) < 0) {
            throw new IllegalArgumentException("사용하려는 금액이 잔액보다 큽니다.");
        }
        this.amountRemaining -= amount;
    }

    public void sharePoints(Long amount) {
        this.amountRemaining += amount;
        this.amountTotal += amount;
    }

    // 만료 여부 확인
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(this.expiredAt);
    }

    // 사용 완료 여부 확인
    public boolean isFullyUsed() {
        return this.amountRemaining == 0;
    }

    // 취소 처리 (소스 타입을 CANCELED로 변경)
    // 이 부분은 lot_status 필드가 새로 추가되었습니다. 그래서 나중에 확인하고 변경하겠습니다.
    public void markAsCanceled() {
        this.lotStatus = LotStatus.CANCELED;
    }
}