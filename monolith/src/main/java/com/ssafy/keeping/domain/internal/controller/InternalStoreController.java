package com.ssafy.keeping.domain.internal.controller;

import com.ssafy.keeping.domain.internal.dto.StoreResponse;
import com.ssafy.keeping.domain.internal.exception.InternalApiAuthException;
import com.ssafy.keeping.domain.store.constant.StoreStatus;
import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.store.repository.StoreRepository;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Internal API - 마이크로서비스 간 통신용
 */
@Slf4j
@RestController
@RequestMapping("/internal/stores")
@RequiredArgsConstructor
public class InternalStoreController {

    private final StoreRepository storeRepository;

    @Value("${internal.auth-token:internal-service-token-12345}")
    private String internalAuthToken;

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

    /**
     * 전체 활성 매장 조회 (Cache Warming용)
     */
    @GetMapping("/all")
    public ResponseEntity<List<StoreResponse>> getAllStores(
            @RequestHeader(value = "X-Internal-Auth", required = false) String authToken
    ) {
        validateInternalAuth(authToken);

        List<Store> stores = storeRepository.findAllActiveStores(StoreStatus.ACTIVE);
        List<StoreResponse> response = stores.stream()
                .map(StoreResponse::from)
                .toList();

        log.info("전체 Store 조회 (Cache Warming): count={}", response.size());
        return ResponseEntity.ok(response);
    }

    private void validateInternalAuth(String authToken) {
        if (!internalAuthToken.equals(authToken)) {
            log.warn("Internal API 인증 실패: 잘못된 토큰");
            throw new InternalApiAuthException("Internal API 인증 실패");
        }
    }
}
