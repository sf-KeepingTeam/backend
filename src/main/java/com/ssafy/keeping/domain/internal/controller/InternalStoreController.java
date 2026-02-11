package com.ssafy.keeping.domain.internal.controller;

import com.ssafy.keeping.domain.internal.dto.StoreResponse;
import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.store.repository.StoreRepository;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Internal API - 마이크로서비스 간 통신용
 */
@Slf4j
@RestController
@RequestMapping("/internal/stores")
@RequiredArgsConstructor
public class InternalStoreController {

    private final StoreRepository storeRepository;

    private static final String INTERNAL_AUTH_TOKEN = "internal-service-token-12345";

    /**
     * 매장 정보 조회
     */
    @GetMapping("/{storeId}")
    public ResponseEntity<StoreResponse> getStore(
            @PathVariable Long storeId,
            @RequestHeader(value = "X-Internal-Auth", required = false) String authToken
    ) {
        validateInternalAuth(authToken);

        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException(ErrorCode.STORE_NOT_FOUND));

        return ResponseEntity.ok(StoreResponse.from(store));
    }

    private void validateInternalAuth(String authToken) {
        if (!INTERNAL_AUTH_TOKEN.equals(authToken)) {
            log.warn("Internal API 인증 실패: 잘못된 토큰");
            throw new IllegalArgumentException("Internal API 인증 실패");
        }
    }
}
