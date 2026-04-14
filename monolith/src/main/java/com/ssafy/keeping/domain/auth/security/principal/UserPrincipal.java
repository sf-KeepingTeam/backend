package com.ssafy.keeping.domain.auth.security.principal;

import com.ssafy.keeping.domain.auth.enums.UserRole;

public record UserPrincipal(
        Long id,
        UserRole role
) {}
