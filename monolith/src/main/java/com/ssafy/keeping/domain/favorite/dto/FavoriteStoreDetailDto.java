package com.ssafy.keeping.domain.favorite.dto;

import java.time.LocalDateTime;

public record FavoriteStoreDetailDto(
        Long storeId,
        String storeName,
        String category,
        String address,
        String imgUrl,
        Long favoriteId,
        LocalDateTime favoritedAt
) {
}