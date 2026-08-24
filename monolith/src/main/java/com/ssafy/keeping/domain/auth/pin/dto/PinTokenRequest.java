package com.ssafy.keeping.domain.auth.pin.dto;

import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class PinTokenRequest {

  private String pin;

  /**
   * 결제 요청의 publicId. null / 빈 문자열이면 세션 토큰 경로로 분기하므로 <b>필수가 아니다</b>.
   *
   * <p>값이 들어온 경우에만 UUID 형식을 강제한다. 형식 검증이 없으면 임의 문자열이 그대로
   * {@code intentPublicId} 클레임에 실려 발급되고, qr-service 검증측에서야 불일치로 거부된다.
   */
  @Pattern(
      regexp =
          "^\\s*$|^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
      message = "intentPublicId는 UUID 형식이어야 합니다.")
  private String intentPublicId;
}
