package com.ssafy.keeping.domain.payment.transactions.model;

import com.ssafy.keeping.domain.menu.model.Menu;
import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;


@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@Entity
@Table(name = "transaction_items",
        indexes = { @Index(name = "idx_ti_tx", columnList = "transaction_id") })
public class TransactionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "item_id")
    private Long itemId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "menu_id")
    private Menu menu;

    @Column(name = "menu_name_snapshot", nullable = false, length = 150)
    private String menuNameSnapshot;

    @Column(name = "menu_price_snapshot", nullable = false)
    private Long menuPriceSnapshot;

    @Column(name = "quantity", nullable = false)
    private Integer quantity;

    @Column(name = "line_total", insertable = false, updatable = false)
    private Long lineTotal;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static TransactionItem of(Transaction tx,
                                     Long storeId,
                                     Menu menuOrNull,
                                     String menuNameSnap,
                                     Long menuPriceSnap,
                                     Integer qty) {

        if (tx.getStore() != null && !tx.getStore().getStoreId().equals(storeId)) {
            throw new CustomException(ErrorCode.STORE_NOT_MATCH); // "트랜잭션과 품목의 가게가 일치하지 않습니다."
        }
        return TransactionItem.builder()
                .transaction(tx)
                .storeId(storeId)
                .menu(menuOrNull)
                .menuNameSnapshot(menuNameSnap)
                .menuPriceSnapshot(menuPriceSnap)
                .quantity(qty)
                .build();
    }

}