package com.ssafy.keeping.qr.domain.intent.controller;

import com.ssafy.keeping.qr.common.response.ApiResponse;
import com.ssafy.keeping.qr.domain.idempotency.model.IdempotentResult;
import com.ssafy.keeping.qr.domain.intent.dto.PaymentInitiateRequest;
import com.ssafy.keeping.qr.domain.intent.dto.PaymentIntentDetailResponse;
import com.ssafy.keeping.qr.domain.intent.service.PaymentIntentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping
@RequiredArgsConstructor
public class PaymentIntentController {

    private final PaymentIntentService paymentIntentService;

    /**
     * 점원/점주가 손님 QR을 스캔하고 결제 의도를 생성
     */
    @PostMapping("/cpqr/{qrTokenId}/initiate")
    public ResponseEntity<ApiResponse<PaymentIntentDetailResponse>> initiate(
            @PathVariable String qrTokenId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKeyHeader,
            @RequestHeader(value = "X-Owner-Id", required = false) Long ownerId,
            @Valid @RequestBody PaymentInitiateRequest body
    ) {
        // TODO: JWT에서 ownerId 추출 (현재는 헤더에서 임시로 받음)
        Long effectiveOwnerId = ownerId != null ? ownerId : 1L;

        IdempotentResult<PaymentIntentDetailResponse> res =
                paymentIntentService.initiate(qrTokenId, idempotencyKeyHeader, effectiveOwnerId, body);

        return ResponseEntity
                .status(res.getHttpStatus())
                .body(ApiResponse.success(
                        res.isReplay() ? "이전에 처리된 요청의 결과입니다." : "결제 요청이 생성되었습니다.",
                        res.getHttpStatus().value(),
                        res.getBody()));
    }

    /**
     * 의도 상세 조회(손님/점주 공용)
     */
    @GetMapping("/api/payments/intent/{intentPublicId}")
    public ResponseEntity<ApiResponse<PaymentIntentDetailResponse>> getDetail(
            @PathVariable UUID intentPublicId
    ) {
        PaymentIntentDetailResponse res = paymentIntentService.getDetail(intentPublicId);
        return ResponseEntity
                .status(HttpStatus.OK)
                .body(ApiResponse.success("OK", HttpStatus.OK.value(), res));
    }
}
