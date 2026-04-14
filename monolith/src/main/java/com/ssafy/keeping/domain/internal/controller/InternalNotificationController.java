package com.ssafy.keeping.domain.internal.controller;

import com.ssafy.keeping.domain.internal.dto.NotificationRequest;
import com.ssafy.keeping.domain.internal.exception.InternalApiAuthException;
import com.ssafy.keeping.domain.notification.entity.NotificationType;
import com.ssafy.keeping.domain.notification.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Internal API - 마이크로서비스 간 통신용
 */
@Slf4j
@RestController
@RequestMapping("/internal/notifications")
@RequiredArgsConstructor
public class InternalNotificationController {

    private final NotificationService notificationService;

    @Value("${internal.auth-token:internal-service-token-12345}")
    private String internalAuthToken;

    /**
     * 알림 발송
     */
    @PostMapping("/send")
    public ResponseEntity<Map<String, String>> sendNotification(
            @RequestBody NotificationRequest request,
            @RequestHeader(value = "X-Internal-Auth", required = false) String authToken
    ) {
        validateInternalAuth(authToken);

        NotificationType notificationType;
        try {
            notificationType = NotificationType.valueOf(request.getNotificationType());
        } catch (IllegalArgumentException e) {
            log.warn("알림 타입 변환 실패: {}", request.getNotificationType());
            notificationType = NotificationType.PAYMENT_APPROVED;
        }

        if ("CUSTOMER".equalsIgnoreCase(request.getReceiverType())) {
            notificationService.sendToCustomer(
                    request.getReceiverId(),
                    notificationType,
                    request.getContent()
            );
        } else if ("OWNER".equalsIgnoreCase(request.getReceiverType())) {
            notificationService.sendToOwner(
                    request.getReceiverId(),
                    notificationType,
                    request.getContent()
            );
        } else {
            log.warn("알 수 없는 수신자 타입: {}", request.getReceiverType());
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Unknown receiver type: " + request.getReceiverType()));
        }

        return ResponseEntity.ok(Map.of("status", "sent"));
    }

    private void validateInternalAuth(String authToken) {
        if (!internalAuthToken.equals(authToken)) {
            log.warn("Internal API 인증 실패: 잘못된 토큰");
            throw new InternalApiAuthException("Internal API 인증 실패");
        }
    }
}
