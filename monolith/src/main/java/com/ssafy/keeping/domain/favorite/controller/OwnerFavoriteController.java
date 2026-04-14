package com.ssafy.keeping.domain.favorite.controller;

import com.ssafy.keeping.domain.auth.security.principal.UserPrincipal;
import com.ssafy.keeping.domain.favorite.dto.StoreFavoriteCountResponseDto;
import com.ssafy.keeping.domain.favorite.service.StoreFavoriteService;
import com.ssafy.keeping.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/favorites/owner")
@RequiredArgsConstructor
public class OwnerFavoriteController {

    private final StoreFavoriteService storeFavoriteService;

    /**
     * 점주의 특정 가게 찜 개수 조회
     */
    @GetMapping("/stores/{storeId}/count")
    public ResponseEntity<ApiResponse<StoreFavoriteCountResponseDto>> getStoreFavoriteCount(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long storeId
    ) {
        Long ownerId = principal.id();
        StoreFavoriteCountResponseDto dto = storeFavoriteService.getStoreFavoriteCount(ownerId, storeId);
        return ResponseEntity.ok(ApiResponse.success("가게 찜 개수 조회에 성공했습니다.", HttpStatus.OK.value(), dto));
    }
}