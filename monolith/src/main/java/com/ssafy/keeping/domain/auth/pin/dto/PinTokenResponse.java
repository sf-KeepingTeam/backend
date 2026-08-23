package com.ssafy.keeping.domain.auth.pin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PinTokenResponse {

  private String pinToken;

  public static PinTokenResponse of(String pinToken) {
    return PinTokenResponse.builder().pinToken(pinToken).build();
  }
}
