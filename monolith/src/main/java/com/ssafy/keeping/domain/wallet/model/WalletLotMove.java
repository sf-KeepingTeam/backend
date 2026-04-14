package com.ssafy.keeping.domain.wallet.model;

import com.ssafy.keeping.domain.payment.transactions.model.Transaction;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "wallet_lot_moves",
        indexes = {
                @Index(name = "idx_moves_tx",  columnList = "transaction_id"),
                @Index(name = "idx_moves_lot", columnList = "lot_id")
        })
public class WalletLotMove {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "move_id")
    private Long moveId;

    /** USE/CANCEL 등 원인 트랜잭션 */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_moves_tx"))
    private Transaction transaction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lot_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_moves_lot"))
    private WalletStoreLot lot;

    /** 증감량: USE는 음수, 취소/복원은 양수 */
    @Column(name = "delta", nullable = false)
    private Long delta;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;


    public static WalletLotMove of(Transaction tx, WalletStoreLot lot, long delta) {
        if (delta == 0L) {
            throw new CustomException(ErrorCode.WALLET_LOT_MOVE_DELTA_ZERO);
        }
        return WalletLotMove.builder()
                .transaction(tx)
                .lot(lot)
                .delta(delta)
                .build();
    }
}
