package com.ssafy.keeping.global.outbox.scheduler;

import com.ssafy.keeping.global.outbox.model.OutboxEvent;
import com.ssafy.keeping.global.outbox.model.OutboxStatus;
import com.ssafy.keeping.global.outbox.repository.OutboxEventRepository;
import com.ssafy.keeping.global.outbox.service.OutboxEventHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Outbox 이벤트 프로세서 (스케줄러)
 *
 * 주기적으로 PENDING 상태의 이벤트를 조회하여 처리합니다.
 * 각 이벤트 유형에 대해 등록된 핸들러를 호출합니다.
 */
@Component
@Slf4j
public class OutboxProcessor {

    private final OutboxEventRepository outboxEventRepository;
    private final Map<String, OutboxEventHandler> handlers = new HashMap<>();

    private static final int BATCH_SIZE = 100;
    private static final int MAX_RETRY = 5;

    public OutboxProcessor(
            OutboxEventRepository outboxEventRepository,
            List<OutboxEventHandler> handlerList) {
        this.outboxEventRepository = outboxEventRepository;

        // 핸들러를 이벤트 유형별로 매핑
        for (OutboxEventHandler handler : handlerList) {
            handlers.put(handler.getEventType(), handler);
            log.info("[Outbox] 핸들러 등록 - eventType: {}", handler.getEventType());
        }
    }

    /**
     * PENDING 이벤트 처리 (5초마다)
     */
    @Scheduled(fixedDelay = 5000)
    public void processPendingEvents() {
        List<OutboxEvent> events = outboxEventRepository.findByStatus(
                OutboxStatus.PENDING,
                PageRequest.of(0, BATCH_SIZE)
        );

        if (events.isEmpty()) {
            return;
        }

        log.debug("[Outbox] 처리 시작 - {} 개 이벤트", events.size());

        for (OutboxEvent event : events) {
            processEvent(event);
        }
    }

    /**
     * 개별 이벤트 처리
     */
    @Transactional
    public void processEvent(OutboxEvent event) {
        String eventType = event.getEventType();
        OutboxEventHandler handler = handlers.get(eventType);

        if (handler == null) {
            log.warn("[Outbox] 핸들러 없음 - eventType: {}, eventId: {}",
                    eventType, event.getId());
            event.markAsFailed("No handler found for event type: " + eventType);
            outboxEventRepository.save(event);
            return;
        }

        try {
            log.debug("[Outbox] 처리 중 - eventType: {}, eventId: {}, aggregateId: {}",
                    eventType, event.getId(), event.getAggregateId());

            handler.handle(event);

            event.markAsPublished();
            outboxEventRepository.save(event);

            log.info("[Outbox] 처리 완료 - eventType: {}, eventId: {}",
                    eventType, event.getId());

        } catch (Exception e) {
            log.error("[Outbox] 처리 실패 - eventType: {}, eventId: {}, retry: {}",
                    eventType, event.getId(), event.getRetryCount(), e);

            String errorMessage = e.getMessage();
            if (errorMessage != null && errorMessage.length() > 1000) {
                errorMessage = errorMessage.substring(0, 1000);
            }
            event.markAsFailed(errorMessage);
            outboxEventRepository.save(event);
        }
    }

    /**
     * 오래된 PUBLISHED 이벤트 정리 (매일 자정)
     */
    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void cleanupOldEvents() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(7);
        int deleted = outboxEventRepository.deletePublishedEventsBefore(
                OutboxStatus.PUBLISHED, threshold);

        if (deleted > 0) {
            log.info("[Outbox] 오래된 이벤트 정리 - {} 개 삭제", deleted);
        }
    }

    /**
     * FAILED 이벤트 모니터링 (1시간마다)
     */
    @Scheduled(cron = "0 0 * * * *")
    public void monitorFailedEvents() {
        long failedCount = outboxEventRepository.countByStatus(OutboxStatus.FAILED);
        long pendingCount = outboxEventRepository.countByStatus(OutboxStatus.PENDING);

        if (failedCount > 0) {
            log.warn("[Outbox] 모니터링 - FAILED: {}, PENDING: {}", failedCount, pendingCount);
        } else {
            log.debug("[Outbox] 모니터링 - PENDING: {}", pendingCount);
        }
    }

    /**
     * 특정 이벤트 수동 재처리
     */
    @Transactional
    public boolean retryEvent(Long eventId) {
        return outboxEventRepository.findById(eventId)
                .map(event -> {
                    if (event.getStatus() == OutboxStatus.FAILED) {
                        event.setStatus(OutboxStatus.PENDING);
                        event.setRetryCount(0);
                        outboxEventRepository.save(event);
                        log.info("[Outbox] 이벤트 재시도 예약 - eventId: {}", eventId);
                        return true;
                    }
                    return false;
                })
                .orElse(false);
    }
}
