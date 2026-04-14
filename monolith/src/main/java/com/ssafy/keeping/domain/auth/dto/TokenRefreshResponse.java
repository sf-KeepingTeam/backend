package com.ssafy.keeping.domain.auth.dto;

public record TokenRefreshResponse(
        String role,
        String accessToken,
        String tokenType,
        long expiresIn
) {
    public static TokenRefreshResponse of(String role, String accessToken, long expiresIn) {
        return new TokenRefreshResponse(role, accessToken, "Bearer", expiresIn);
    }
}
