package com.ssafy.keeping.domain.menu.controller;

import com.ssafy.keeping.domain.auth.security.principal.UserPrincipal;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/owners/stores/{storeId}/menus")
@RequiredArgsConstructor
public class OwnerMenuController {
    private final MenuService menuService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<MenuResponseDto>> createMenus(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long storeId,
            @Valid @ModelAttribute MenuRequestDto requestDto
    ) {
        Long ownerId = principal.id();
        MenuResponseDto dto = menuService.createMenu(ownerId, storeId, requestDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("메뉴가 등록되었습니다", HttpStatus.CREATED.value(), dto));
    }

    @PatchMapping(value="/{menusId}" , consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<MenuResponseDto>> editMenus(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long menusId,
            @PathVariable Long storeId,
            @Valid @ModelAttribute MenuEditRequestDto requestDto
    ) {
        Long ownerId = principal.id();
        MenuResponseDto dto = menuService.editMenu(ownerId, storeId, menusId, requestDto);
        return ResponseEntity.ok(ApiResponse.success("메뉴가 수정되었습니다", HttpStatus.OK.value(), dto));
    }

    @GetMapping()
    public ResponseEntity<ApiResponse<List<MenuResponseDto>>> getAllMenus(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long storeId
    ) {
        Long ownerId = principal.id();
        List<MenuResponseDto> dtos = menuService.getAllMenusForOwner(ownerId, storeId);
        return ResponseEntity.ok(ApiResponse.success("메뉴가 전체 조회되었습니다", HttpStatus.OK.value(), dtos));
    }

    @DeleteMapping("/{menusId}")
    public ResponseEntity<ApiResponse<Void>> deleteMenu(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long storeId,
            @PathVariable Long menusId
    ) {
        Long ownerId = principal.id();
        menuService.deleteMenu(ownerId, storeId, menusId);
        return ResponseEntity.ok(ApiResponse.success("메뉴가 삭제 되었습니다", HttpStatus.OK.value(), null));
    }

    @DeleteMapping()
    public ResponseEntity<ApiResponse<Void>> deleteMenu(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long storeId
    ) {
        Long ownerId = principal.id();
        menuService.deleteAllMenu(ownerId, storeId);
        return ResponseEntity.ok(ApiResponse.success("메뉴가 전체 삭제 되었습니다", HttpStatus.OK.value(), null));
    }
}
