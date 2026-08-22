package com.ssafy.keeping.domain.internal.controller;

import com.ssafy.keeping.domain.internal.service.InternalAuthValidator;
import com.ssafy.keeping.domain.wallet.dto.ExpirySweepReport;
import com.ssafy.keeping.domain.wallet.dto.ReconcileReport;
import com.ssafy.keeping.domain.wallet.service.LotExpiryService;
import com.ssafy.keeping.domain.wallet.service.WalletReconciliationService;
import com.ssafy.keeping.global.constants.HttpHeaderConstants;
import com.ssafy.keeping.global.response.ApiResponse;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영 엔드포인트 — 부하테스트 직후 정합성 판정용.
 *
 * <p>스케줄 대기 없이 즉시 만료 정산·대사를 돌릴 수 있다.
 * {@code /internal/*} 이므로 Nginx가 외부 차단하고 {@code validateInternalAuth}로 한 번 더 검증한다.
 *
 * <p><b>주의:</b> 대사는 전 지갑을 훑는 무거운 작업이다. 프론트 트래픽과 같은 톰캣 스레드풀·Hikari 풀을
 * 공유하므로, 운영 중에 함부로 부르면 결제가 느려질 수 있다.
 */
@RestController
@RequestMapping("/internal/ops")
@RequiredArgsConstructor
public class InternalOpsController {

  private final InternalAuthValidator internalAuthValidator;
  private final LotExpiryService lotExpiryService;
  private final WalletReconciliationService walletReconciliationService;

  @PostMapping("/lot-expiry/run")
  public ResponseEntity<ApiResponse<ExpirySweepReport>> runLotExpiry(
      @RequestHeader(value = HttpHeaderConstants.X_INTERNAL_AUTH, required = false)
          String authToken) {
    internalAuthValidator.validate(authToken);
    ExpirySweepReport report = lotExpiryService.sweepOnce(LocalDateTime.now());
    return ResponseEntity.ok(ApiResponse.success("만료 정산 완료", 200, report));
  }

  @PostMapping("/reconcile/run")
  public ResponseEntity<ApiResponse<ReconcileReport>> runReconcile(
      @RequestHeader(value = HttpHeaderConstants.X_INTERNAL_AUTH, required = false)
          String authToken) {
    internalAuthValidator.validate(authToken);
    ReconcileReport report = walletReconciliationService.runOnce();
    return ResponseEntity.ok(ApiResponse.success("대사 완료", 200, report));
  }
}
