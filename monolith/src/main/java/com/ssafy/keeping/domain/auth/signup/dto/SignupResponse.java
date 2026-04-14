package com.ssafy.keeping.domain.auth.signup.dto;

public record SignupResponse(
        RegisterResponse user,
        AuthResponse auth
) {
    public static SignupResponse of(RegisterResponse user, AuthResponse auth) {
        return new SignupResponse(user, auth);
    }
}
