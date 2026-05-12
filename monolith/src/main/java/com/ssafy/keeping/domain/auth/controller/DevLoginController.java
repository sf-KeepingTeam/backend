package com.ssafy.keeping.domain.auth.controller;

import com.ssafy.keeping.domain.auth.cookie.RefreshCookieManager;
import com.ssafy.keeping.domain.auth.enums.UserRole;
import com.ssafy.keeping.domain.auth.token.AccessTokenService;
import com.ssafy.keeping.domain.auth.token.RefreshTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 개발/테스트 전용 로그인 우회 컨트롤러
 *
 * loadtest.backdoor.enabled=true 일 때만 활성화됩니다.
 * Kakao OAuth 없이 userId + role 만으로 AccessToken + RefreshToken을 즉시 발급합니다.
 *
 * 사용 예시:
 * POST /auth/dev-login
 * { "userId": 1, "role": "CUSTOMER" }
 *
 * 응답:
 * - body: { accessToken, tokenType, expiresIn, role, userId }
 * - Set-Cookie: REFRESH_TOKEN=<uuid>; HttpOnly; Path=/auth
 */
@RestController
@RequestMapping("/auth")
@ConditionalOnProperty(name = "loadtest.backdoor.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DevLoginController {

    private final AccessTokenService accessTokenService;
    private final RefreshTokenService refreshTokenService;
    private final RefreshCookieManager cookieManager;

    @PostMapping("/dev-login")
    public ResponseEntity<DevLoginResponse> devLogin(@RequestBody DevLoginRequest request) {

        UserRole role;
        try {
            role = UserRole.valueOf(request.role().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        Long userId = request.userId();

        // 1. RefreshToken 발급 (Redis에 저장, 기존 세션 있으면 폐기)
        RefreshTokenService.IssuedRefreshToken issued =
                refreshTokenService.issueSingleSession(userId, role);

        // 2. AccessToken 발급 (subject = userId 문자열, 기존 OAuth2 흐름과 동일)
        String accessToken = accessTokenService.issueAccessToken(
                String.valueOf(userId), role
        );

        // 3. RefreshToken 쿠키 설정
        var cookie = cookieManager.issue(issued.token(), issued.ttlSeconds());

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(new DevLoginResponse(
                        accessToken,
                        "Bearer",
                        accessTokenService.accessTtlSeconds(),
                        role.name(),
                        userId
                ));
    }

    public record DevLoginRequest(
            Long userId,
            String role   // "CUSTOMER" 또는 "OWNER"
    ) {}

    public record DevLoginResponse(
            String accessToken,
            String tokenType,
            long expiresIn,
            String role,
            Long userId
    ) {}
}
