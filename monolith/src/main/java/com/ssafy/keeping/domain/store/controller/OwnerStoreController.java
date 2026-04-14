package com.ssafy.keeping.domain.store.controller;

import com.ssafy.keeping.domain.auth.security.principal.UserPrincipal;
import com.ssafy.keeping.domain.store.dto.StoreEditRequestDto;
import com.ssafy.keeping.domain.store.dto.StorePublicDto;
import com.ssafy.keeping.domain.store.dto.StoreRequestDto;
import com.ssafy.keeping.domain.store.dto.StoreResponseDto;
import com.ssafy.keeping.domain.store.service.StoreService;
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
@RequestMapping("/owners/stores")
@RequiredArgsConstructor
public class OwnerStoreController {
    private final StoreService storeService;

    /*
     * 가게 주인이 사용하는 api - 가게 등록 post
     * */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<StoreResponseDto>> createStore(
            @AuthenticationPrincipal UserPrincipal principal,
            @Valid @ModelAttribute StoreRequestDto requestDto
    ) {
        Long ownerId = principal.id();
        StoreResponseDto dto = storeService.createStore(ownerId, requestDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("매장이 등록되었습니다", HttpStatus.CREATED.value(), dto));
    }

    /*
     * 가게 주인이 사용하는 api - 가게 수정 patch
     * */
    @PatchMapping(value = "/{storeId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<StoreResponseDto>> editStore(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long storeId,
            @Valid @ModelAttribute StoreEditRequestDto requestDto
    ) {
        Long ownerId = principal.id();
        StoreResponseDto dto = storeService.editStore(storeId, ownerId, requestDto);
        return ResponseEntity.ok(ApiResponse.success("매장이 수정되었습니다", HttpStatus.OK.value(), dto));
    }

    /*
     * 가게 주인이 사용하는 api - 가게 삭제 delete
     * */
    @DeleteMapping("/{storeId}")
    public ResponseEntity<ApiResponse<StoreResponseDto>> deleteStore(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long storeId
    ) {
        Long ownerId = principal.id();
        return ResponseEntity.ok(ApiResponse.success("매장이 삭제되었습니다", HttpStatus.OK.value(),
                storeService.deleteStore(storeId, ownerId)));
    }

    /*
     * 점주의 모든 매장 조회 GET
     * */
    @GetMapping
    public ResponseEntity<ApiResponse<List<StoreResponseDto>>> getMyStores(
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        Long ownerId = principal.id();
        List<StoreResponseDto> stores = storeService.getMyStores(ownerId);
        return ResponseEntity.ok(ApiResponse.success("내 매장 목록이 조회되었습니다.", HttpStatus.OK.value(), stores));
    }

    // TODO: 가게 주인 자신이 볼 수 있는 전체 정보(민감정보 마스킹 or 마스킹 X)
    @GetMapping("/{storeId}")
    public ResponseEntity<ApiResponse<StorePublicDto>> getStore(
            @PathVariable Long storeId
    ) {
        return ResponseEntity.ok(ApiResponse.success("해당 store id로 매장이 조회되었습니다.", HttpStatus.OK.value(), storeService.getStoreByStoreId(storeId)));
    }
}