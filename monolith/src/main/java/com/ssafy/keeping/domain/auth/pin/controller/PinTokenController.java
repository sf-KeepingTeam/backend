package com.ssafy.keeping.domain.auth.pin.controller;

import com.ssafy.keeping.domain.auth.pin.dto.PinTokenRequest;
import com.ssafy.keeping.domain.auth.pin.dto.PinTokenResponse;
import com.ssafy.keeping.domain.auth.pin.service.PinTokenService;
import com.ssafy.keeping.domain.auth.security.principal.UserPrincipal;
import com.ssafy.keeping.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 고객이 PIN을 검증하고 단기 서명 토큰을 발급받는 엔드포인트.
 *
 * <p>발급된 토큰은 qr-service의 /approve 호출 시 PIN 대신 첨부한다.
 * qr-service는 토큰 서명을 로컬에서 검증하므로 monolith 호출이 불필요해진다.
 *
 * <p>경로: {@code /customers/pin/verify-token} — SecurityConfig의
 * {@code /customers/**} 규칙에 의해 CUSTOMER 역할만 접근 가능.
 */
@RestController
@RequestMapping("/customers/pin")
@RequiredArgsConstructor
public class PinTokenController {

  private final PinTokenService pinTokenService;

  /**
   * PIN 검증 후 단기 서명 토큰 발급.
   *
   * @param principal SecurityContext에서 추출한 인증 고객 정보
   * @param request PIN + intentPublicId (선택. 값이 있으면 UUID 형식이어야 한다)
   * @return 서명 토큰
   */
  @PostMapping("/verify-token")
  public ResponseEntity<ApiResponse<PinTokenResponse>> verifyAndIssueToken(
      @AuthenticationPrincipal UserPrincipal principal,
      @RequestBody @Valid PinTokenRequest request) {

    PinTokenResponse response =
        pinTokenService.verifyAndIssueToken(
            principal.id(), request.getPin(), request.getIntentPublicId());

    return ResponseEntity.ok(
        ApiResponse.success("PIN 검증 토큰 발급 성공", 200, response));
  }
}
