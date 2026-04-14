package com.ssafy.keeping.domain.favorite.dto;

public record FavoriteToggleResponseDto(
        Long customerId,
        Long storeId,
        boolean isFavorited,
        Long favoriteId
) {
}