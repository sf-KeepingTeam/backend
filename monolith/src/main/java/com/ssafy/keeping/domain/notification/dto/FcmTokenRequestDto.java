package com.ssafy.keeping.domain.notification.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class FcmTokenRequestDto {

  @NotBlank(message = "FCM 토큰은 필수입니다.")
  private String token;
}
