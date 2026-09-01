package com.ssafy.keeping.qr.domain.intent.canonical;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.*;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"pin", "pinToken"})
public class CanonicalApprove {
  private String pin;
  /** 토큰 경로에서는 토큰 문자열 자체를 정규화 대상으로 포함 */
  private String pinToken;
}
