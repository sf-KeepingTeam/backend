package com.ssafy.keeping.qr.saga.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.qr.acl.NotificationClient;
import com.ssafy.keeping.qr.saga.constant.SagaEventType;
import com.ssafy.keeping.qr.saga.dto.NotificationPayload;
import com.ssafy.keeping.qr.saga.model.SagaLog;
import com.ssafy.keeping.qr.saga.service.SagaLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Outbox 패턴 프로세서
 * PENDING 상태의 Saga 이벤트를 주기적으로 처리
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxProcessor {

    private final SagaLogService sagaLogService;
    private final NotificationClient notificationClient;
    private final ObjectMapper objectMapper;

    /**
     * 30초마다 대기 중인 이벤트 처리
     */
    @Scheduled(fixedDelay = 30000)
    public void processPendingEvents() {
        List<SagaLog> pendingEvents = sagaLogService.findPendingEventsForProcessing();

        if (pendingEvents.isEmpty()) {
            return;
        }

        log.info("OutboxProcessor: {} 개의 대기 이벤트 처리 시작", pendingEvents.size());

        for (SagaLog event : pendingEvents) {
            processEvent(event);
        }
    }

    private void processEvent(SagaLog event) {
        try {
            // 처리중 상태로 변경
            sagaLogService.markProcessing(event);

            // 이벤트 타입에 따라 처리
            switch (event.getEventType()) {
                case NOTIFICATION_REQUEST:
                case NOTIFICATION_APPROVED:
                    processNotification(event);
                    break;
                case FUNDS_CAPTURE:
                case FUNDS_RESTORE:
                    // 자금 관련 이벤트는 현재 동기 처리 유지
                    // 추후 비동기로 전환 시 구현
                    log.warn("자금 이벤트는 현재 동기 처리됨 - eventId: {}", event.getId());
                    sagaLogService.markCompleted(event);
                    break;
                default:
                    log.warn("알 수 없는 이벤트 타입 - eventType: {}", event.getEventType());
                    sagaLogService.markCompleted(event);
            }

        } catch (Exception e) {
            log.error("Saga 이벤트 처리 실패 - id: {}, error: {}", event.getId(), e.getMessage());
            sagaLogService.scheduleRetry(event, e.getMessage());
        }
    }

    private void processNotification(SagaLog event) {
        try {
            NotificationPayload payload = objectMapper.treeToValue(
                    event.getPayload(),
                    NotificationPayload.class
            );

            // 알림 전송
            if ("CUSTOMER".equals(payload.getTargetType())) {
                notificationClient.sendToCustomer(
                        payload.getTargetId(),
                        payload.getNotificationType(),
                        payload.getContent()
                );
            } else if ("OWNER".equals(payload.getTargetType())) {
                notificationClient.sendToOwner(
                        payload.getTargetId(),
                        payload.getNotificationType(),
                        payload.getContent()
                );
            }

            sagaLogService.markCompleted(event);

            log.info("알림 이벤트 처리 완료 - aggregateId: {}, targetType: {}, targetId: {}",
                    event.getAggregateId(), payload.getTargetType(), payload.getTargetId());

        } catch (Exception e) {
            throw new RuntimeException("알림 처리 실패: " + e.getMessage(), e);
        }
    }
}
