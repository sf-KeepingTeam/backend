package com.ssafy.keeping.domain.auth.signup.dto;

import com.ssafy.keeping.domain.auth.enums.UserRole;

public record SignupResult(
        long userId,
        UserRole role,
        RegisterResponse userDto
) {}