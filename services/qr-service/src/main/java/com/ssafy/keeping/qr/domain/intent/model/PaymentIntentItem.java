package com.ssafy.keeping.qr.domain.intent.model;

import jakarta.persistence.*;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "payment_intent_item",
        indexes = @Index(name = "idx_intent", columnList = "intent_id"))
public class PaymentIntentItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "intent_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_item_intent"))
    private PaymentIntent intent;

    @Column(name = "menu_id", nullable = false)
    private Long menuId;

    @Column(name = "menu_name_snap", nullable = false, length = 100)
    private String menuNameSnap;

    @Column(name = "unit_price_snap", nullable = false)
    private long unitPriceSnap; // KRW

    @Column(nullable = false)
    private int quantity;

    @Column(name = "line_total", insertable = false, updatable = false)
    private Long lineTotal;
}
