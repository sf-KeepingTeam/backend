package com.ssafy.keeping.domain.menucategory.controller;

import com.ssafy.keeping.domain.menucategory.dto.MenuCategoryResponseDto;
import com.ssafy.keeping.domain.menucategory.service.MenuCategoryService;
import com.ssafy.keeping.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("stores/{storeId}/menus/categories")
@RequiredArgsConstructor
public class MenuCategoryController {
    private final MenuCategoryService menuCategoryService;

    /*
     * 고객이 가게 메뉴 카테고리를 위한 api - 가게 메뉴 카테고리 전체 조회
     * */
    // 현재는 parent id가 null인 대분류 카테고리만 반환
    @GetMapping()
    public ResponseEntity<ApiResponse<List<MenuCategoryResponseDto>>> getAllMenuCategory(
            @PathVariable Long storeId
    ) {
        List<MenuCategoryResponseDto> dtoList = menuCategoryService.getAllMajorCategory(storeId);
        return ResponseEntity.ok(ApiResponse.success("해당 가게의 메뉴 카테고리(대분류)가 전체 조회되었습니다.", HttpStatus.OK.value(), dtoList));
    }
}
