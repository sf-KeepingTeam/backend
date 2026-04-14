package com.ssafy.keeping.domain.menucategory.model;

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
import java.util.Objects;

@Entity
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(
        name = "categories",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_cat_name_per_parent",
                        columnNames = {"store_id","parent_id","category_name"}),
                @UniqueConstraint(name = "uq_cat_order_per_parent",
                        columnNames = {"store_id","parent_id","display_order"})
        },
        indexes = {
                @Index(name = "idx_categories_store",  columnList = "store_id"),
                @Index(name = "idx_categories_parent", columnList = "parent_id"),
                @Index(name = "idx_cat_store_parent_order", columnList = "store_id,parent_id,display_order")
        }
)
@EntityListeners(AuditingEntityListener.class)
public class MenuCategory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "category_id")
    private Long categoryId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_categories_store"))
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id",
            foreignKey = @ForeignKey(name = "fk_categories_parent"))
    private MenuCategory parent;

    @Column(name = "category_name", nullable = false, length = 100)
    private String categoryName;

    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private Integer displayOrder = 0;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public void changeNameAndParent(String name, MenuCategory parent) {
        if (!Objects.equals(name, this.categoryName)) this.categoryName = name;
        if (!Objects.equals(parent, this.parent)) this.parent = parent;
    }

    public void changeOrder(int order) {
        if (!Objects.equals(order, this.displayOrder)) this.displayOrder = order;
    }
}
