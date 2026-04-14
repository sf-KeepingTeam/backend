package com.ssafy.keeping.domain.store.dto;

import com.ssafy.keeping.domain.store.constant.StoreStatus;
import com.ssafy.keeping.domain.store.model.Store;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Builder
@Getter
public class StoreResponseDto {

    private Long storeId;
    private String storeName;
    private String address;
    private String phoneNumber;
    private String category;
    private StoreStatus storeStatus;
    private String description;
    private LocalDateTime createdAt;
    private String imgUrl;

    public static StoreResponseDto fromEntity(Store store) {
        return StoreResponseDto.builder()
                .storeId(store.getStoreId())
                .storeName(store.getStoreName())
                .address(store.getAddress())
                .phoneNumber(store.getPhoneNumber())
                .category(store.getCategory())
                .createdAt(store.getCreatedAt())
                .storeStatus(store.getStoreStatus())
                .description(store.getDescription())
                .imgUrl(store.getImgUrl())
                .build();
    }
}
