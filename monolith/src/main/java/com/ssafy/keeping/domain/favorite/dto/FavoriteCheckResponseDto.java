package com.ssafy.keeping.domain.favorite.dto;

public record FavoriteCheckResponseDto(
        Long customerId,
        Long storeId,
        boolean isFavorited,
        Long favoriteId
) {
}