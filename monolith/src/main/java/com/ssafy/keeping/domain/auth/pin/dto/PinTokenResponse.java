package com.ssafy.keeping.domain.auth.pin.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * PIN 토큰 발급 응답 DTO.
 *
 * <p><b>Jackson 직렬화 주의</b>: 파생 게터({@code isXxx()}/계산 게터)를 추가하지 마라.
 * {@code FAIL_ON_UNKNOWN_PROPERTIES}가 켜진 역직렬화 측에서 500이 난다 (v2 QrToken.isExpired() 사례).
 * 순수 필드만 둔다.
 *
 * <p>필드:
 * <ul>
 *   <li>{@code pinToken} — 서명된 JWT (기존, 모든 경로)</li>
 *   <li>{@code expiresAtEpochSec} — 만료 epoch 초. 클라이언트가 재발급 시점 판단에 사용 (신규)</li>
 *   <li>{@code maxUses} — 세션 토큰일 때 최대 사용 횟수({@code mu} 클레임값). intent 토큰이면 null (신규)</li>
 * </ul>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PinTokenResponse {

  private String pinToken;

  /** 토큰 만료 시각(Unix epoch 초). 클라이언트가 재발급 여부 판단에 사용. */
  private Long expiresAtEpochSec;

  /**
   * 세션 토큰의 최대 사용 횟수({@code mu} 클레임값). intent 토큰이면 {@code null}.
   * 클라이언트가 남은 횟수를 트래킹할 때 사용.
   */
  private Integer maxUses;

  /**
   * intent 토큰 발급 시 사용 (기존 호출부 호환 유지).
   * expiresAtEpochSec·maxUses는 null로 채워진다.
   */
  public static PinTokenResponse of(String pinToken) {
    return PinTokenResponse.builder().pinToken(pinToken).build();
  }

  /**
   * 토큰 발급 시 만료 시각과 최대 사용 횟수를 함께 실어 반환한다.
   *
   * @param pinToken  서명된 JWT
   * @param expiresAtEpochSec 만료 epoch 초
   * @param maxUses   세션 토큰의 mu 클레임값. intent 토큰이면 null
   */
  public static PinTokenResponse of(String pinToken, Long expiresAtEpochSec, Integer maxUses) {
    return PinTokenResponse.builder()
        .pinToken(pinToken)
        .expiresAtEpochSec(expiresAtEpochSec)
        .maxUses(maxUses)
        .build();
  }
}
