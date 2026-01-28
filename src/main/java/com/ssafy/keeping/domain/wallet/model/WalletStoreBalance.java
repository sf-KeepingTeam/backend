package com.ssafy.keeping.domain.wallet.model;

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

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    /**
     * 가게 이름 스냅샷 (Pattern 3: 데이터 복제)
     * MSA 전환 시 StoreRepository 의존성 제거를 위해 생성 시점에 저장
     * 가게 이름 변경 시 StoreNameChangedEvent로 동기화
     */
    @Column(name = "store_name_snapshot", length = 100)
    private String storeNameSnapshot;

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