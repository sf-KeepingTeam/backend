package com.ssafy.keeping.domain.menucategory.dto;

import java.time.LocalDateTime;

public record MenuCategoryResponseDto(
        Long categoryId, Long storeId, Long parentId, String categoryName,
        Integer displayOrder, LocalDateTime createdAt
) {}
