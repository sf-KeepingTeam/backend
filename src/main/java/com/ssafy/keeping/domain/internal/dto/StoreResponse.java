package com.ssafy.keeping.domain.internal.dto;

import com.ssafy.keeping.domain.store.model.Store;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoreResponse {
    private Long storeId;
    private String storeName;
    private Long ownerId;
    private String taxIdNumber;
    private String address;
    private boolean isActive;

    public static StoreResponse from(Store store) {
        return StoreResponse.builder()
                .storeId(store.getStoreId())
                .storeName(store.getStoreName())
                .ownerId(store.getOwner() != null ? store.getOwner().getOwnerId() : null)
                .taxIdNumber(store.getTaxIdNumber())
                .address(store.getAddress())
                .isActive(true)
                .build();
    }
}
