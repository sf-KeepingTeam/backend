package com.ssafy.keeping.domain.favorite.controller;

import com.ssafy.keeping.domain.auth.security.principal.UserPrincipal;
import com.ssafy.keeping.domain.favorite.dto.FavoriteCheckResponseDto;
import com.ssafy.keeping.domain.favorite.dto.FavoriteToggleResponseDto;
import com.ssafy.keeping.domain.favorite.dto.StoreFavoriteResponseDto;
import com.ssafy.keeping.domain.favorite.service.StoreFavoriteService;
import com.ssafy.keeping.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/favorites")
@RequiredArgsConstructor
public class StoreFavoriteController {

    private final StoreFavoriteService storeFavoriteService;

    /**
     * 좋아요 버튼 누르기
     */
    @PostMapping("/stores/{storeId}")
    public ResponseEntity<ApiResponse<FavoriteToggleResponseDto>> toggleFavorite(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long storeId
    ) {
        Long customerId = principal.id();
        FavoriteToggleResponseDto dto = storeFavoriteService.toggleFavorite(customerId, storeId);
        String message = dto.isFavorited() ? "찜 추가에 성공했습니다." : "찜 취소에 성공했습니다.";
        return ResponseEntity.ok(ApiResponse.success(message, HttpStatus.OK.value(), dto));
    }

    /**
     * 좋아요 목록 보기
     */
    @GetMapping
    public ResponseEntity<ApiResponse<StoreFavoriteResponseDto>> getFavoriteStores(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        Long customerId = principal.id();
        Pageable pageable = PageRequest.of(page, size);
        StoreFavoriteResponseDto dto = storeFavoriteService.getFavoriteStores(customerId, pageable);
        return ResponseEntity.ok(ApiResponse.success("찜 목록 조회에 성공했습니다.", HttpStatus.OK.value(), dto));
    }

    /**
     * 가게 좋아요 눌러져있는지 확인하는 것 (찜 버튼 on / off 여부)
     */
    @GetMapping("/stores/{storeId}/check")
    public ResponseEntity<ApiResponse<FavoriteCheckResponseDto>> checkFavoriteStatus(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long storeId
    ) {
        Long customerId = principal.id();
        FavoriteCheckResponseDto dto = storeFavoriteService.checkFavoriteStatus(customerId, storeId);
        return ResponseEntity.ok(ApiResponse.success("찜 상태 확인에 성공했습니다.", HttpStatus.OK.value(), dto));
    }
}