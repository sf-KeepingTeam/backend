package com.ssafy.keeping.domain.menu.model;

import com.ssafy.keeping.domain.menucategory.model.MenuCategory;
import com.ssafy.keeping.domain.store.model.Store;
import jakarta.persistence.*;
import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Table(
        name = "menus",
        uniqueConstraints = {
                @UniqueConstraint(name = "uq_order_per_category",  columnNames = {"store_id","category_id","display_order"})
        },
        indexes = {
                @Index(name = "idx_menus_store",    columnList = "store_id"),
                @Index(name = "idx_menus_category", columnList = "category_id"),
                @Index(name = "idx_menus_active",   columnList = "active"),
                @Index(name = "idx_menus_soldout",  columnList = "sold_out")
        }
)
@SQLDelete(sql = "UPDATE menus SET deleted_at = NOW(3) WHERE menu_id = ?")
@Where(clause = "deleted_at IS NULL")
@EntityListeners(AuditingEntityListener.class)
public class Menu {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "menu_id")
    private Long menuId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "store_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_menus_store"))
    private Store store;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false,
            foreignKey = @ForeignKey(name = "fk_menus_category"))
    private MenuCategory category;

    @Column(name = "menu_name", nullable = false, length = 150)
    private String menuName;

    @Column(nullable = false)
    @Min(1000)
    private int price;

    @Column(nullable = true, length = 500)
    private String description;

    @Column(name = "sold_out", nullable = false)
    @Builder.Default
    private boolean soldOut = false;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    @Column(name = "image_url", nullable = false, length = 500)
    private String imgUrl;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public void editMenu(String menuName, String imgUrl,
                         int price, String description, int order) {
        if (!Objects.equals(menuName, this.menuName)) this.menuName = menuName;
        if (!Objects.equals(imgUrl, this.imgUrl)) this.imgUrl = imgUrl;
        if (this.price != price) this.price = price;
        if (!Objects.equals(description, this.description)) this.description = description;
        if (this.displayOrder != order) this.displayOrder = order;
    }

    public void changeCategory(MenuCategory category) {
        if (category != null) this.category = category;
    }
}
