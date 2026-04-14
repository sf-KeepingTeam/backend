package com.ssafy.keeping.domain.favorite.model;

import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.store.model.Store;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "store_favorites",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_customer_store_favorite",
                columnNames = {"customer_id", "store_id"}
        ),
        indexes = {
                @Index(name = "idx_favorite_customer", columnList = "customer_id"),
                @Index(name = "idx_favorite_store", columnList = "store_id"),
                @Index(name = "idx_favorite_active", columnList = "active")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class StoreFavorite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "favorite_id")
    private Long favoriteId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;

    @Column(name = "active", nullable = false)
    private boolean active;

    @CreationTimestamp
    @Column(name = "favorited_at", nullable = false)
    private LocalDateTime favoritedAt;

    @Column(name = "unfavorited_at")
    private LocalDateTime unfavoritedAt;

    public boolean isActive() {
        return this.active;
    }

    public void cancel() {
        this.active = false;
        this.unfavoritedAt = LocalDateTime.now();
    }

    public void reactivate() {
        this.active = true;
        this.favoritedAt = LocalDateTime.now();
        this.unfavoritedAt = null;
    }
}