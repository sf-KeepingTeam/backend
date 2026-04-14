package com.ssafy.keeping.domain.payment.toss;

import com.ssafy.keeping.domain.payment.toss.config.TossPaymentConfig;
import com.ssafy.keeping.domain.payment.toss.dto.TossCancelRequest;
import com.ssafy.keeping.domain.payment.toss.dto.TossCancelResponse;
import com.ssafy.keeping.domain.payment.toss.dto.TossPaymentConfirmRequest;
import com.ssafy.keeping.domain.payment.toss.dto.TossPaymentConfirmResponse;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 토스페이먼츠 API 클라이언트
 * 토스 API와의 HTTP 통신을 담당
 */
@Slf4j
@Component
public class TossPaymentClient {

    private final TossPaymentConfig config;
    private final RestTemplate restTemplate;

    public TossPaymentClient(TossPaymentConfig config) {
        this.config = config;
        this.restTemplate = new RestTemplate();
    }

    /**
     * 결제 승인 API 호출
     * POST /v1/payments/confirm
     */
    public TossPaymentConfirmResponse confirmPayment(TossPaymentConfirmRequest request) {
        String url = config.getBaseUrl() + "/v1/payments/confirm";

//        log.info("[토스] 결제 승인 요청 - paymentKey: {}, orderId: {}, amount: {}",
//                request.getPaymentKey(), request.getOrderId(), request.getAmount());

        try {
            HttpEntity<TossPaymentConfirmRequest> entity = new HttpEntity<>(request, createHeaders());

            ResponseEntity<TossPaymentConfirmResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    TossPaymentConfirmResponse.class
            );

            TossPaymentConfirmResponse body = response.getBody();

            if (body != null && body.isSuccess()) {
//                log.info("[토스] 결제 승인 성공 - paymentKey: {}, status: {}",
//                        body.getPaymentKey(), body.getStatus());
            } else {
//                log.warn("[토스] 결제 승인 실패 - code: {}, message: {}",
//                        body != null ? body.getCode() : "unknown",
//                        body != null ? body.getMessage() : "unknown");
            }

            return body;

        } catch (HttpClientErrorException e) {
//            log.error("[토스] 결제 승인 API 오류 - status: {}, body: {}",
//                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new CustomException(ErrorCode.PAYMENT_CONFIRM_FAILED);
        } catch (Exception e) {
//            log.error("[토스] 결제 승인 중 예외 발생", e);
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR);
        }
    }

    /**
     * 결제 취소 API 호출
     * POST /v1/payments/{paymentKey}/cancel
     */
    public TossCancelResponse cancelPayment(String paymentKey, TossCancelRequest request) {
        String url = config.getBaseUrl() + "/v1/payments/" + paymentKey + "/cancel";

//        log.info("[토스] 결제 취소 요청 - paymentKey: {}, cancelReason: {}, cancelAmount: {}",
//                paymentKey, request.getCancelReason(), request.getCancelAmount());

        try {
            HttpEntity<TossCancelRequest> entity = new HttpEntity<>(request, createHeaders());

            ResponseEntity<TossCancelResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    entity,
                    TossCancelResponse.class
            );

            TossCancelResponse body = response.getBody();

            if (body != null && body.isSuccess()) {
//                log.info("[토스] 결제 취소 성공 - paymentKey: {}, status: {}",
//                        body.getPaymentKey(), body.getStatus());
            } else {
//                log.warn("[토스] 결제 취소 실패 - code: {}, message: {}",
//                        body != null ? body.getCode() : "unknown",
//                        body != null ? body.getMessage() : "unknown");
            }

            return body;

        } catch (HttpClientErrorException e) {
//            log.error("[토스] 결제 취소 API 오류 - status: {}, body: {}",
//                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new CustomException(ErrorCode.PAYMENT_CANCEL_FAILED);
        } catch (Exception e) {
//            log.error("[토스] 결제 취소 중 예외 발생", e);
            throw new CustomException(ErrorCode.EXTERNAL_API_ERROR);
        }
    }

    /**
     * 토스 API 인증 헤더 생성
     * Basic Auth: Base64(secretKey:)
     */
    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Basic Auth: secretKey + ":" 를 Base64 인코딩
        String credentials = config.getSecretKey() + ":";
        String encodedCredentials = Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        headers.set("Authorization", "Basic " + encodedCredentials);

        return headers;
    }
}
