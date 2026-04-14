package com.ssafy.keeping.domain.store.dto;

import com.ssafy.keeping.domain.store.constant.StoreStatus;

import java.time.LocalDateTime;

// 공개 리스트/검색 전용
public record StorePublicDto(
        Long storeId, String storeName, String address, String phoneNumber,
        String category, StoreStatus storeStatus, String description,
        LocalDateTime createdAt, String imgUrl
) {}
