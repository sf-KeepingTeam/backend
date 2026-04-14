package com.ssafy.keeping.domain.auth.signup.dto;

import com.ssafy.keeping.domain.auth.enums.Gender;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record OwnerSignupRequest(
        @NotBlank
        String ticket,

        @NotBlank
        String name,

        @NotNull
        LocalDate birth,

        @NotNull
        Gender gender,

        @NotBlank
        @Email
        String email,

        @NotBlank
        String phoneNumber
) {}
