package com.ssafy.keeping.qr.domain.qr.controller;

import com.ssafy.keeping.qr.common.response.ApiResponse;
import com.ssafy.keeping.qr.domain.intent.dto.IntentArrivalResponse;
import com.ssafy.keeping.qr.domain.intent.service.IntentWaitRegistry;
import com.ssafy.keeping.qr.domain.qr.dto.QrCreateRequest;
import com.ssafy.keeping.qr.domain.qr.dto.QrCreateResponse;
import com.ssafy.keeping.qr.domain.qr.dto.QrScanResponse;
import com.ssafy.keeping.qr.domain.qr.dto.QrTokenResponse;
import com.ssafy.keeping.qr.domain.qr.model.QrToken;
import com.ssafy.keeping.qr.domain.qr.repository.QrFlowRedisStore;
import com.ssafy.keeping.qr.domain.qr.service.QrTokenService;
import com.ssafy.keeping.qr.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;

@RestController
@RequestMapping("/api/qr")
@RequiredArgsConstructor
public class QrController {

  private final QrTokenService qrTokenService;
  private final QrFlowRedisStore qrFlowRedisStore;
  private final IntentWaitRegistry intentWaitRegistry;

  /** QR 토큰 생성 POST /api/qr */
  @PostMapping
  public ResponseEntity<ApiResponse<QrCreateResponse>> createQr(
      @Valid @RequestBody QrCreateRequest request,
      @AuthenticationPrincipal UserPrincipal principal) {
    QrCreateResponse response = qrTokenService.createQrToken(request, principal.id());
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(ApiResponse.success("QR 토큰이 생성되었습니다.", HttpStatus.CREATED.value(), response));
  }

  /** QR 토큰 조회 GET /api/qr/{tokenId} */
  @GetMapping("/{tokenId}")
  public ResponseEntity<ApiResponse<QrTokenResponse>> getQr(@PathVariable String tokenId) {
    QrToken token = qrTokenService.getValidToken(tokenId);
    return ResponseEntity.ok(
        ApiResponse.success("OK", HttpStatus.OK.value(), QrTokenResponse.from(token)));
  }

  /**
   * QR 스캔 및 세션 토큰 발급 POST /api/qr/{tokenId}/scan
   *
   * <p>점주가 고객 QR을 스캔하면: 1. QR 토큰 검증 (10초 TTL) 2. QR 토큰 즉시 삭제 (재사용 방지) 3. 세션 토큰 발급 (3분 TTL)
   *
   * <p>이후 결제 요청은 세션 토큰으로 진행
   */
  @PostMapping("/{tokenId}/scan")
  public ResponseEntity<ApiResponse<QrScanResponse>> scanQr(@PathVariable String tokenId) {
    QrScanResponse response = qrTokenService.scanAndConsumeQr(tokenId);
    return ResponseEntity.ok(
        ApiResponse.success("QR 스캔 완료. 세션 토큰이 발급되었습니다.", HttpStatus.OK.value(), response));
  }

  /** QR 토큰 삭제 DELETE /api/qr/{tokenId} */
  @DeleteMapping("/{tokenId}")
  public ResponseEntity<Void> deleteQr(@PathVariable String tokenId) {
    qrTokenService.deleteToken(tokenId);
    return ResponseEntity.noContent().build();
  }

  /**
   * 결제 의도 도착 롱폴링 GET /api/qr/{tokenId}/intent
   *
   * <p>손님이 QR 화면에서 호출. 점주 initiate 완료 시 200 + {@link IntentArrivalResponse} 반환.
   * 25 초 내 도착 없으면 204 No Content (손님이 재시도).
   *
   * <ul>
   *   <li>G-1: active 키 없음 → 404 (QR 스캔 전 또는 만료)
   *   <li>G-2: customerId 불일치 → 403
   *   <li>타임아웃 → 204 (DeferredResult timeout handler)
   * </ul>
   */
  @GetMapping("/{tokenId}/intent")
  public DeferredResult<ResponseEntity<ApiResponse<IntentArrivalResponse>>> waitForIntent(
      @PathVariable String tokenId,
      @AuthenticationPrincipal UserPrincipal principal) {

    // G-1: QR 스캔이 완료된 tokenId 인지 확인
    if (!qrFlowRedisStore.isActiveToken(tokenId)) {
      DeferredResult<ResponseEntity<ApiResponse<IntentArrivalResponse>>> immediate =
          new DeferredResult<>();
      immediate.setResult(ResponseEntity.notFound().build());
      return immediate;
    }

    // 25 초 타임아웃 → 204 No Content (손님 클라이언트가 재시도)
    DeferredResult<ResponseEntity<ApiResponse<IntentArrivalResponse>>> result =
        new DeferredResult<>(25_000L, ResponseEntity.noContent().build());

    intentWaitRegistry.register(tokenId, result, principal.id());
    return result;
  }
}
