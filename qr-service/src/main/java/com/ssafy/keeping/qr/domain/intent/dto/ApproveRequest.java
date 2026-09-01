package com.ssafy.keeping.qr.domain.intent.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 결제 승인 요청.
 *
 * <p>PIN 또는 PIN 토큰 중 하나를 제출한다.
 * <ul>
 *   <li>{@code pinToken}이 있으면 → 로컬 토큰 검증 (monolith 왕복 없음)
 *   <li>{@code pinToken}이 없으면 → 기존 PIN HTTP 검증 ({@code pin} 필수)
 * </ul>
 *
 * <p>Bean Validation 을 제거하고 서비스 계층에서 분기 검증한다.
 * 이전 {@code @NotBlank @Pattern} 은 토큰 경로에서 요청을 차단하므로 삭제.
 */
@Getter
@NoArgsConstructor
public class ApproveRequest {

  /** 6자리 PIN (토큰 경로에서는 null 허용) */
  private String pin;

  /** monolith가 발급한 PIN 검증 토큰 (JWT). 있으면 PIN 대신 로컬 검증 */
  private String pinToken;
}
