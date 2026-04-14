package com.ssafy.keeping.domain.favorite.dto;

import org.springframework.data.domain.Page;

public record StoreFavoriteResponseDto(
        Long customerId,
        long totalFavoriteCount,
        Page<SimpleFavoriteDto> favoriteStores
) {
}