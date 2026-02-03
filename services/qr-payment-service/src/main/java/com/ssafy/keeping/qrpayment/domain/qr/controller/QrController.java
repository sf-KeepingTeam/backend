package com.ssafy.keeping.qrpayment.domain.qr.controller;

import com.ssafy.keeping.qrpayment.common.response.ApiResponse;
import com.ssafy.keeping.qrpayment.domain.qr.dto.QrCreateRequest;
import com.ssafy.keeping.qrpayment.domain.qr.dto.QrCreateResponse;
import com.ssafy.keeping.qrpayment.domain.qr.model.QrToken;
import com.ssafy.keeping.qrpayment.domain.qr.service.QrTokenService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/qr")
@RequiredArgsConstructor
public class QrController {

    private final QrTokenService qrTokenService;

    /**
     * QR 토큰 생성
     * POST /api/qr
     */
    @PostMapping
    public ResponseEntity<ApiResponse<QrCreateResponse>> createQr(
            @Valid @RequestBody QrCreateRequest request,
            @RequestHeader(value = "X-Customer-Id", required = false) Long customerId
    ) {
        // customerId가 없으면 임시값 사용 (테스트용)
        Long effectiveCustomerId = customerId != null ? customerId : 1L;

        QrCreateResponse response = qrTokenService.createQrToken(request, effectiveCustomerId);
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }

    /**
     * QR 토큰 조회
     * GET /api/qr/{tokenId}
     */
    @GetMapping("/{tokenId}")
    public ResponseEntity<ApiResponse<QrToken>> getQr(
            @PathVariable String tokenId
    ) {
        QrToken token = qrTokenService.getValidToken(tokenId);
        return ResponseEntity.ok(ApiResponse.success(token));
    }

    /**
     * QR 토큰 삭제
     * DELETE /api/qr/{tokenId}
     */
    @DeleteMapping("/{tokenId}")
    public ResponseEntity<Void> deleteQr(
            @PathVariable String tokenId
    ) {
        qrTokenService.deleteToken(tokenId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 헬스체크 (서비스 식별용)
     */
    @GetMapping("/health")
    public ResponseEntity<ApiResponse<String>> health() {
        return ResponseEntity.ok(ApiResponse.success("qr-payment-service"));
    }
}
