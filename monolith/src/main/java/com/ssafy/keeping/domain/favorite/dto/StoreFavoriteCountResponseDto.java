package com.ssafy.keeping.domain.favorite.dto;

public record StoreFavoriteCountResponseDto(
        Long storeId,
        String storeName,
        long favoriteCount
) {
}