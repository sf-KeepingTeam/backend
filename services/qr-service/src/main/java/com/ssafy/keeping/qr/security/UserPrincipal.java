package com.ssafy.keeping.qr.security;

/**
 * JWT에서 추출한 인증된 사용자 정보
 */
public record UserPrincipal(
        Long id,
        UserRole role
) {}
