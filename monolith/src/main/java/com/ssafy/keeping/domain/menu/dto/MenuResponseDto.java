package com.ssafy.keeping.domain.menu.dto;

public record MenuResponseDto(
        Long menuId,
        Long storeId,
        String menuName,
        Long categoryId,
        String categoryName,
        int displayOrder,
        boolean soldOut,
        String imgUrl,
        String description,
        int price
) { }