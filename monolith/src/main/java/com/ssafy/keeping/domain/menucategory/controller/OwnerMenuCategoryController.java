package com.ssafy.keeping.domain.menucategory.controller;

import com.ssafy.keeping.domain.auth.security.principal.UserPrincipal;
import com.ssafy.keeping.domain.menucategory.dto.MenuCategoryEditRequestDto;
import com.ssafy.keeping.domain.menucategory.dto.MenuCategoryRequestDto;
import com.ssafy.keeping.domain.menucategory.dto.MenuCategoryResponseDto;
import com.ssafy.keeping.domain.menucategory.service.MenuCategoryService;
import com.ssafy.keeping.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/owners/stores/{storeId}/menus/categories")
@RequiredArgsConstructor
public class OwnerMenuCategoryController {
    private final MenuCategoryService menuCategoryService;

    /*
     * 가게 주인이 가게 메뉴 카테고리를 위한 api - 가게 메뉴 카테고리 등록
     * */
    @PostMapping()
    public ResponseEntity<ApiResponse<MenuCategoryResponseDto>> createMenuCategory(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long storeId,
            @RequestBody MenuCategoryRequestDto requestDto
    ) {
        Long ownerId = principal.id();
        MenuCategoryResponseDto dto = menuCategoryService.createMenuCategory(ownerId, storeId, requestDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("메뉴 카테고리가 등록되었습니다", HttpStatus.CREATED.value(), dto));
    }

    /*
     * 가게 주인이 가게 메뉴 카테고리를 위한 api - 가게 메뉴 카테고리 전체 조회
     * */
    // TODO: front 화면이 세부 카테고리도 필요한 경우 api 추가 등록
    // 현재는 parent id가 null인 대분류 카테고리만 보여지게 됩니다.
    @GetMapping()
    public ResponseEntity<ApiResponse<List<MenuCategoryResponseDto>>> getAllMenuCategory(
            @PathVariable Long storeId
    ) {
        List<MenuCategoryResponseDto> dtoList = menuCategoryService.getAllMajorCategory(storeId);
        return ResponseEntity.ok(ApiResponse.success("해당 가게의 메뉴 카테고리(대분류)가 전체 조회되었습니다.", HttpStatus.OK.value(), dtoList));
    }

    /*
     * 가게 주인이 가게 메뉴 카테고리를 위한 api - 가게 메뉴 카테고리 수정
     * */
    @PatchMapping("/{categoryId}")
    public ResponseEntity<ApiResponse<MenuCategoryResponseDto>> editMenuCategory(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long storeId,
            @PathVariable Long categoryId,
            @RequestBody MenuCategoryEditRequestDto requestDto
    ) {
        Long ownerId = principal.id();
        MenuCategoryResponseDto dto = menuCategoryService.editMenuCategory(ownerId, storeId, categoryId, requestDto);
        return ResponseEntity.ok(ApiResponse.success("메뉴 카테고리가 수정되었습니다", HttpStatus.OK.value(), dto));
    }

    /*
     * 가게 주인이 가게 메뉴 카테고리를 위한 api - 가게 메뉴 카테고리 삭제
     * */
    @DeleteMapping("{categoryId}")
    public ResponseEntity<ApiResponse<Void>> deleteMenuCategory(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long storeId,
            @PathVariable Long categoryId
    ) {
        Long ownerId = principal.id();
        menuCategoryService.deleteMenuCategory(ownerId, storeId, categoryId);
        return ResponseEntity.ok(ApiResponse.success("메뉴 카테고리가 삭제되었습니다", HttpStatus.OK.value(), null));
    }
}
