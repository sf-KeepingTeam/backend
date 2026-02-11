package com.ssafy.keeping.domain.internal.controller;

import com.ssafy.keeping.domain.internal.dto.BatchMenuRequest;
import com.ssafy.keeping.domain.internal.dto.MenuResponse;
import com.ssafy.keeping.domain.menu.model.Menu;
import com.ssafy.keeping.domain.menu.repository.MenuRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Internal API - 마이크로서비스 간 통신용
 */
@Slf4j
@RestController
@RequestMapping("/internal/menus")
@RequiredArgsConstructor
public class InternalMenuController {

    private final MenuRepository menuRepository;

    private static final String INTERNAL_AUTH_TOKEN = "internal-service-token-12345";

    /**
     * 메뉴 일괄 조회
     */
    @PostMapping("/batch")
    public ResponseEntity<List<MenuResponse>> getMenusBatch(
            @RequestBody BatchMenuRequest request,
            @RequestHeader(value = "X-Internal-Auth", required = false) String authToken
    ) {
        validateInternalAuth(authToken);

        List<Menu> menus = menuRepository.findAllById(request.getMenuIds());

        List<MenuResponse> response = menus.stream()
                .map(MenuResponse::from)
                .collect(Collectors.toList());

        return ResponseEntity.ok(response);
    }

    /**
     * 단일 메뉴 조회
     */
    @GetMapping("/{menuId}")
    public ResponseEntity<MenuResponse> getMenu(
            @PathVariable Long menuId,
            @RequestHeader(value = "X-Internal-Auth", required = false) String authToken
    ) {
        validateInternalAuth(authToken);

        Menu menu = menuRepository.findById(menuId)
                .orElse(null);

        if (menu == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(MenuResponse.from(menu));
    }

    private void validateInternalAuth(String authToken) {
        if (!INTERNAL_AUTH_TOKEN.equals(authToken)) {
            log.warn("Internal API 인증 실패: 잘못된 토큰");
            throw new IllegalArgumentException("Internal API 인증 실패");
        }
    }
}
