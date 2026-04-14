package com.ssafy.keeping.domain.auth.signup.dto;

import com.ssafy.keeping.domain.auth.enums.UserRole;

public record AuthResponse(
        String tokenType,   // "Bearer"
        String accessToken,
        UserRole role        // CUSTOMER / OWNER (선택)
        // String expiresAt   // 필요하면 추가 (ISO-8601)
) {
    public static AuthResponse bearer(String accessToken, UserRole role) {
        return new AuthResponse("Bearer", accessToken, role);
    }
}