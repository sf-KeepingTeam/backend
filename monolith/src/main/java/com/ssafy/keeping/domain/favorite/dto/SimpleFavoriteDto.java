package com.ssafy.keeping.domain.favorite.dto;

import java.time.LocalDateTime;

public record SimpleFavoriteDto(
        Long favoriteId,
        Long storeId,
        LocalDateTime favoritedAt
) {
}