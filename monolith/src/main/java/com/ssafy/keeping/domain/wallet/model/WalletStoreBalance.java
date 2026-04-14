package com.ssafy.keeping.domain.wallet.model;

import com.ssafy.keeping.domain.store.model.Store;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Entity
@Table(
        name = "wallet_store_balances",
        uniqueConstraints = @UniqueConstraint(name = "uk_wallet_store", columnNames = {"wallet_id","store_id"}),
        indexes = {@Index(name="idx_wsb_wallet", columnList="wallet_id"),
                @Index(name="idx_wsb_store",  columnList="store_id")}
)
public class WalletStoreBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "balance_id")
    private Long balanceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(name = "balance", nullable = false)
    private Long balance;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public void addBalance(Long amount) {
        this.balance = this.balance + amount;
    }

    public void subtractBalance(Long amount) {
        if (this.balance < amount) {
            // TODO: 커스텀익셉션 으로 변경하기
            throw new IllegalArgumentException("잔액 부족: " + this.balance + " < " + amount);
        }
        this.balance = this.balance - amount;
    }
}