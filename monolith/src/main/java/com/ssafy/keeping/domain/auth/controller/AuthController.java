package com.ssafy.keeping.domain.auth.controller;

import com.ssafy.keeping.domain.auth.cookie.RefreshCookieManager;
import com.ssafy.keeping.domain.auth.dto.TokenRefreshResponse;
import com.ssafy.keeping.domain.auth.token.AccessTokenService;
import com.ssafy.keeping.domain.auth.token.RefreshTokenService;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import com.ssafy.keeping.global.response.ApiResponse;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final RefreshTokenService refreshTokenService;
    private final AccessTokenService accessTokenService;
    private final RefreshCookieManager refreshCookieManager;

    /**
     * Nginx auth_request용 JWT 검증 엔드포인트
     * - 성공 시: 200 + 사용자 정보 헤더
     * - 실패 시: 401
     */
    @GetMapping("/verify")
    public ResponseEntity<Void> verify(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletResponse response
    ) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.debug("인증 헤더 없음 또는 형식 오류");
            return ResponseEntity.status(401).build();
        }

        try {
            String token = authHeader.substring(7); // "Bearer " 제거
            Claims claims = accessTokenService.parseClaims(token);

            // 사용자 정보를 응답 헤더에 담음 (Nginx가 전달)
            String userId = claims.getSubject();
            String role = claims.get("role", String.class);

            response.setHeader("X-User-Id", userId);
            response.setHeader("X-User-Role", role);

            // role에 따라 Customer/Owner ID 설정
            if ("CUSTOMER".equals(role)) {
                response.setHeader("X-Customer-Id", userId);
            } else if ("OWNER".equals(role)) {
                response.setHeader("X-Owner-Id", userId);
            }

            log.debug("JWT 검증 성공: userId={}, role={}", userId, role);
            return ResponseEntity.ok().build();

        } catch (Exception e) {
            log.debug("JWT 검증 실패: {}", e.getMessage());
            return ResponseEntity.status(401).build();
        }
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<TokenRefreshResponse>> refresh(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = extractCookie(request, refreshCookieManager.cookieName());

        // 쿠키가 없으면 재발급 불가
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new CustomException(ErrorCode.REFRESH_TOKEN_MISSING);
        }

        // rotate
        var rotated = refreshTokenService.rotateSingleSession(refreshToken);
        if (rotated == null) {
            ResponseCookie clear = refreshCookieManager.clear();
            response.addHeader(HttpHeaders.SET_COOKIE, clear.toString());
            throw new CustomException(ErrorCode.REFRESH_TOKEN_INVALID);
        }

        // 새 refresh 쿠키 세팅
        ResponseCookie cookie = refreshCookieManager.issue(rotated.token(), rotated.ttlSeconds());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        // 새 access 발급 (role/userId는 rotate 과정에서 payload로 이미 검증)
        var payload = rotated.payload();
        String accessToken = accessTokenService.issueAccessToken(String.valueOf(payload.userId()), payload.role());

        TokenRefreshResponse body = TokenRefreshResponse.of(
                payload.role().name(),
                accessToken,
                accessTokenService.accessTtlSeconds()
        );

        return ResponseEntity.ok(ApiResponse.success("토큰 재발급 성공", 200, body));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = extractCookie(request, refreshCookieManager.cookieName());

        // Redis 토큰 정리
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokenService.logoutSingleSession(refreshToken);
        }

        // refresh 쿠키 삭제(Set-Cookie)
        ResponseCookie clear = refreshCookieManager.clear();
        response.addHeader(HttpHeaders.SET_COOKIE, clear.toString());

        return ResponseEntity.ok(ApiResponse.success("로그아웃 완료", 200, null));
    }

    // ===== Helper Method =====

    private String extractCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;

        for (Cookie c : cookies) {
            if (name.equals(c.getName())) return c.getValue();
        }
        return null;
    }
}
