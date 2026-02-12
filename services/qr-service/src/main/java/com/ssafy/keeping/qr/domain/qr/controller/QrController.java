package com.ssafy.keeping.qr.domain.qr.controller;

import com.ssafy.keeping.qr.common.response.ApiResponse;
import com.ssafy.keeping.qr.domain.qr.dto.QrCreateRequest;
import com.ssafy.keeping.qr.domain.qr.dto.QrCreateResponse;
import com.ssafy.keeping.qr.domain.qr.dto.QrTokenResponse;
import com.ssafy.keeping.qr.domain.qr.model.QrToken;
import com.ssafy.keeping.qr.domain.qr.service.QrTokenService;
import com.ssafy.keeping.qr.security.UserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        QrCreateResponse response = qrTokenService.createQrToken(request, principal.id());
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success("QR 토큰이 생성되었습니다.", HttpStatus.CREATED.value(), response));
    }

    /**
     * QR 토큰 조회
     * GET /api/qr/{tokenId}
     */
    @GetMapping("/{tokenId}")
    public ResponseEntity<ApiResponse<QrTokenResponse>> getQr(
            @PathVariable String tokenId
    ) {
        QrToken token = qrTokenService.getValidToken(tokenId);
        return ResponseEntity.ok(ApiResponse.success("OK", HttpStatus.OK.value(), QrTokenResponse.from(token)));
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
}
