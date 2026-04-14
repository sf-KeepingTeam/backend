package com.ssafy.keeping.domain.notification.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import jakarta.validation.constraints.NotBlank;

@Getter
@Setter
@NoArgsConstructor
public class FcmTokenRequestDto {

    @NotBlank(message = "FCM 토큰은 필수입니다.")
    private String token;
}