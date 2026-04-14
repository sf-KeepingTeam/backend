package com.ssafy.keeping.domain.menu.controller;

import com.ssafy.keeping.domain.menu.dto.MenuEditRequestDto;
import com.ssafy.keeping.domain.menu.dto.MenuRequestDto;
import com.ssafy.keeping.domain.menu.dto.MenuResponseDto;
import com.ssafy.keeping.domain.menu.service.MenuService;
import com.ssafy.keeping.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/stores/{storeId}/menus")
@RequiredArgsConstructor
public class MenuController {
    private final MenuService menuService;

    @GetMapping()
    public ResponseEntity<ApiResponse<List<MenuResponseDto>>> getAllMenus(
            @PathVariable Long storeId
    ) {
        List<MenuResponseDto> dtos = menuService.getAllMenus(storeId);
        return ResponseEntity.ok(ApiResponse.success("메뉴가 전체 조회되었습니다", HttpStatus.OK.value(), dtos));
    }

    @GetMapping("/categories/{categoryId}")
    public ResponseEntity<ApiResponse<List<MenuResponseDto>>> getAllMenusByCategories(
            @PathVariable Long storeId,
            @PathVariable Long categoryId
    ) {
        List<MenuResponseDto> dtos = menuService.getAllMenusByCategory(categoryId);
        return ResponseEntity.ok(ApiResponse.success("카테고리 별로 메뉴가 전체 조회되었습니다", HttpStatus.OK.value(), dtos));
    }
}
