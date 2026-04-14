package com.ssafy.keeping.domain.auth.signup.dto;

import com.ssafy.keeping.domain.auth.enums.Gender;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;

public record CustomerSignupRequest(
        @NotBlank
        String ticket,

        @NotBlank
        String name,

        @NotNull
        LocalDate birth,

        @NotNull
        Gender gender,

        @NotBlank
        @Email(message = "이메일 형식이 올바르지 않습니다.")
        String email,

        @NotBlank
        String phoneNumber,

        @NotBlank
        @Pattern(regexp = "^[0-9]{6}$", message = "PIN은 숫자 6자리여야 합니다")
        String pin
) {}
