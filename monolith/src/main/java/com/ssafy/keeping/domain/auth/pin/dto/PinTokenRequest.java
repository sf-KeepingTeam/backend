package com.ssafy.keeping.domain.auth.pin.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class PinTokenRequest {

  private String pin;
  private String intentPublicId;
}
