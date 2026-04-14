package com.ssafy.keeping.domain.auth.signup.dto;

public record IssuedAuthTokens(
        SignupResponse body,
        String refreshToken,
        long refreshTtlSeconds
) {
}
