package com.ssafy.keeping.domain.charge.model;

import com.ssafy.keeping.domain.store.model.Store;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "charge_bonus",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"store_id", "charge_amount"})
        })
@EntityListeners(AuditingEntityListener.class)
public class ChargeBonus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "charge_bonus_id")
    private Long chargeBonusId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_charge_bonus_store"))
    private Store store;

    @Column(name = "charge_amount", nullable = false)
    private Long chargeAmount;

    @Column(name = "bonus_percentage", nullable = false)
    private Integer bonusPercentage;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public void updateChargeBonus(Long chargeAmount, Integer bonusPercentage) {
        if (chargeAmount != null) {
            this.chargeAmount = chargeAmount;
        }
        if (bonusPercentage != null) {
            this.bonusPercentage = bonusPercentage;
        }
    }
}