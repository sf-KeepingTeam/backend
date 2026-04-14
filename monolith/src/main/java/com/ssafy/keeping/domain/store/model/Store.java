package com.ssafy.keeping.domain.store.model;

import com.ssafy.keeping.domain.user.owner.model.Owner;
import com.ssafy.keeping.domain.payment.transactions.model.Transaction;
import com.ssafy.keeping.domain.wallet.model.WalletStoreBalance;
import com.ssafy.keeping.domain.wallet.model.WalletStoreLot;
import com.ssafy.keeping.domain.store.constant.StoreStatus;
import com.ssafy.keeping.domain.store.dto.StoreEditRequestDto;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

@Entity
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "stores",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"tax_id_number", "address"})
        })
@EntityListeners(AuditingEntityListener.class)
public class Store {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "store_id")
    private Long storeId;

    // Store N : 1 Owner 연관관계
     @ManyToOne(fetch = FetchType.LAZY, optional = false)
     @JoinColumn(name = "owner_id", nullable = false,
         foreignKey = @ForeignKey(name = "fk_stores_owner"))
     private Owner owner;

    @Column(name = "tax_id_number", nullable = false)
    private String taxIdNumber;

    @Column(name = "store_name", nullable = false)
    private String storeName;

    @Column(nullable = false)
    private String address;

    @Column(name = "phone_number")
    private String phoneNumber;

    @Column(nullable = false)
    private String category;

    // TODO file system은 나중에
    @Column(name = "img_url", nullable = false)
    private String imgUrl;

    @Column(length = 250)
    private String description;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "stores_status", nullable = false)
    private StoreStatus storeStatus;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    // 연관관계
    @OneToMany(mappedBy = "store", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<WalletStoreBalance> walletStoreBalances;

    @OneToMany(mappedBy = "store", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<WalletStoreLot> walletStoreLots;

    @OneToMany(mappedBy = "store", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Transaction> transactions;

    public void patchStore(StoreEditRequestDto requestDto, String imgUrl) {
        if (!Objects.equals(this.storeName, requestDto.getStoreName())) this.storeName = requestDto.getStoreName();
        if (!Objects.equals(this.address,    requestDto.getAddress()))    this.address = requestDto.getAddress();
        if (!Objects.equals(this.phoneNumber,requestDto.getPhoneNumber()))this.phoneNumber = requestDto.getPhoneNumber();
        if (!Objects.equals(this.imgUrl,     imgUrl))                     this.imgUrl = imgUrl;
    }

    // TODO: 점주 탈퇴 시 사용하여 유령가게 방지
    public void deleteStore(StoreStatus storeStatus) {
        if (!Objects.equals(StoreStatus.DELETED, storeStatus)) this.deletedAt = LocalDateTime.now();
        this.storeStatus = storeStatus;
    }
}