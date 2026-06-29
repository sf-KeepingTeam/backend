package com.ssafy.keeping.domain.payment.toss;

import com.ssafy.keeping.domain.payment.toss.config.TossPaymentConfig;
import com.ssafy.keeping.domain.payment.toss.dto.TossCancelRequest;
import com.ssafy.keeping.domain.payment.toss.dto.TossCancelResponse;
import com.ssafy.keeping.domain.payment.toss.dto.TossPaymentConfirmRequest;
import com.ssafy.keeping.domain.payment.toss.dto.TossPaymentConfirmResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 실제 토스페이먼츠 API를 호출하는 클라이언트.
 * payment.toss.mode=real 일 때 활성화된다.
 * 인증: Basic base64(secretKey + ":")
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "payment.toss.mode", havingValue = "real")
public class RealTossPaymentClient implements TossPaymentClient {

    private final RestClient restClient;
    private final String authorizationHeader;

    public RealTossPaymentClient(TossPaymentConfig config, RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.getConnectTimeout());
        factory.setReadTimeout(config.getReadTimeout());

        this.restClient = builder
                .baseUrl(config.getBaseUrl())
                .requestFactory(factory)
                .build();

        String raw = config.getSecretKey() + ":";
        this.authorizationHeader = "Basic " + Base64.getEncoder()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public TossPaymentConfirmResponse confirmPayment(TossPaymentConfirmRequest request) {
        log.info("[TossReal] 결제 승인 호출 - orderId: {}, amount: {}",
                request.getOrderId(), request.getAmount());

        TossPaymentConfirmResponse response = restClient.post()
                .uri("/v1/payments/confirm")
                .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    // 4xx/5xx도 예외로 던지지 않고 body(code/message)를 그대로 역직렬화한다.
                })
                .body(TossPaymentConfirmResponse.class);

        if (response == null) {
            response = new TossPaymentConfirmResponse();
            response.setCode("EMPTY_RESPONSE");
            response.setMessage("토스 응답이 비어 있습니다.");
        }
        if (!response.isSuccess()) {
            log.warn("[TossReal] 결제 승인 실패 - code: {}, message: {}",
                    response.getCode(), response.getMessage());
        }
        return response;
    }

    @Override
    public TossCancelResponse cancelPayment(String paymentKey, TossCancelRequest request) {
        log.info("[TossReal] 결제 취소 호출 - paymentKey: {}, cancelAmount: {}",
                paymentKey, request.getCancelAmount());

        TossCancelResponse response = restClient.post()
                .uri("/v1/payments/{paymentKey}/cancel", paymentKey)
                .header(HttpHeaders.AUTHORIZATION, authorizationHeader)
                .header("Idempotency-Key", "cancel:" + paymentKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    // 동일: 에러 body를 역직렬화
                })
                .body(TossCancelResponse.class);

        if (response == null) {
            response = new TossCancelResponse();
            response.setCode("EMPTY_RESPONSE");
            response.setMessage("토스 응답이 비어 있습니다.");
        }
        if (!response.isSuccess()) {
            log.warn("[TossReal] 결제 취소 실패 - code: {}, message: {}",
                    response.getCode(), response.getMessage());
        }
        return response;
    }
}
