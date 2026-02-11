package com.ssafy.keeping.qr.saga.scheduler;

import com.ssafy.keeping.qr.domain.intent.repository.PaymentIntentRepository;
import com.ssafy.keeping.qr.saga.model.SagaLog;
import com.ssafy.keeping.qr.saga.service.SagaLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Recovery Job
 * - 만료된 PaymentIntent 자동 EXPIRED 처리
 * - 오래된 Saga 이벤트 경고
 * - FAILED 상태 이벤트 모니터링
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecoveryJob {

    private final PaymentIntentRepository paymentIntentRepository;
    private final SagaLogService sagaLogService;

    /**
     * 5분마다 만료된 PaymentIntent 처리
     */
    @Scheduled(fixedDelay = 300000)
    @Transactional
    public void expireStalePaymentIntents() {
        LocalDateTime now = LocalDateTime.now();

        int expiredCount = paymentIntentRepository.expirePendingIntents(now);

        if (expiredCount > 0) {
            log.info("RecoveryJob: {} 개의 만료된 PaymentIntent 처리 완료", expiredCount);
        }
    }

    /**
     * 5분마다 오래된 Saga 이벤트 경고
     */
    @Scheduled(fixedDelay = 300000)
    public void warnStaleEvents() {
        List<SagaLog> staleEvents = sagaLogService.findStaleEvents();

        if (!staleEvents.isEmpty()) {
            log.warn("RecoveryJob: {} 개의 Saga 이벤트가 5분 이상 PENDING 상태", staleEvents.size());

            for (SagaLog event : staleEvents) {
                log.warn("  - Stale event: id={}, aggregateId={}, eventType={}, createdAt={}",
                        event.getId(),
                        event.getAggregateId(),
                        event.getEventType(),
                        event.getCreatedAt());
            }
        }
    }

    /**
     * 10분마다 FAILED 상태 자금 캡처 이벤트 모니터링
     * 보상 트랜잭션이 필요한 경우 경고 로그 출력
     */
    @Scheduled(fixedDelay = 600000)
    public void monitorFailedFundsCapture() {
        List<SagaLog> failedEvents = sagaLogService.findFailedFundsCaptureEvents();

        if (!failedEvents.isEmpty()) {
            log.error("RecoveryJob: {} 개의 FAILED 자금 캡처 이벤트 발견 - 수동 확인 필요",
                    failedEvents.size());

            for (SagaLog event : failedEvents) {
                log.error("  - Failed funds capture: id={}, aggregateId={}, errorMessage={}",
                        event.getId(),
                        event.getAggregateId(),
                        event.getErrorMessage());
            }
        }
    }
}
