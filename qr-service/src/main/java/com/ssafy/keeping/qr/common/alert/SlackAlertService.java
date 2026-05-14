package com.ssafy.keeping.qr.common.alert;

import com.ssafy.keeping.qr.domain.intent.model.PaymentIntent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

@Slf4j
@Service
public class SlackAlertService implements AlertService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RestTemplate restTemplate;
    private final String webhookUrl;

    public SlackAlertService(
            RestTemplate restTemplate,
            @Value("${slack.webhook-url:}") String webhookUrl
    ) {
        this.restTemplate = restTemplate;
        this.webhookUrl = webhookUrl;
    }

    @Override
    public void notifyRecoveryFailed(PaymentIntent intent, String reason) {
        String message = String.format(
                "\uD83D\uDEA8 [RECOVERY_FAILED] 수동 확인 필요\n"
                        + "• intentId: %d\n"
                        + "• publicId: %s\n"
                        + "• 고객 ID: %d / 매장 ID: %d\n"
                        + "• 금액: %d원\n"
                        + "• 실패 사유: %s\n"
                        + "• 발생 시각: %s",
                intent.getIntentId(),
                intent.getPublicId(),
                intent.getCustomerId(),
                intent.getStoreId(),
                intent.getAmount(),
                reason,
                LocalDateTime.now().format(FORMATTER)
        );
        send(message);
    }

    @Override
    public void notifyCircuitBreakerOpen(String clientName) {
        String message = String.format(
                "⚡ [CIRCUIT_BREAKER_OPEN] 서킷브레이커 오픈\n"
                        + "• 대상: %s\n"
                        + "• 발생 시각: %s",
                clientName,
                LocalDateTime.now().format(FORMATTER)
        );
        send(message);
    }

    @Override
    public void notifyRecoveryWarning(PaymentIntent intent, int retryCount) {
        String message = String.format(
                "⚠\uFE0F [RECOVERY_WARNING] 복구 재시도 5회 이상\n"
                        + "• intentId: %d\n"
                        + "• 현재 재시도 횟수: %d\n"
                        + "• 발생 시각: %s",
                intent.getIntentId(),
                retryCount,
                LocalDateTime.now().format(FORMATTER)
        );
        send(message);
    }

    private void send(String message) {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            log.warn("Slack webhook URL이 설정되지 않아 알림을 건너뜁니다: {}", message);
            return;
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            Map<String, String> body = Map.of("text", message);
            HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

            restTemplate.postForEntity(webhookUrl, request, String.class);
            log.info("Slack 알림 전송 완료");
        } catch (Exception e) {
            log.warn("Slack 알림 전송 실패: {}", e.getMessage());
        }
    }
}
