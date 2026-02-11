package com.ssafy.keeping.qr.saga.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.qr.saga.constant.SagaEventType;
import com.ssafy.keeping.qr.saga.constant.SagaStatus;
import com.ssafy.keeping.qr.saga.constant.TargetService;
import com.ssafy.keeping.qr.saga.model.SagaLog;
import com.ssafy.keeping.qr.saga.repository.SagaLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SagaLogService {

    private final SagaLogRepository sagaLogRepository;
    private final ObjectMapper objectMapper;

    // 지수 백오프 기본 간격 (초)
    private static final int BASE_RETRY_DELAY_SECONDS = 30;

    /**
     * Saga 이벤트 발행
     * 새 트랜잭션에서 실행하여 메인 트랜잭션과 분리
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SagaLog publish(String aggregateId,
                           SagaEventType eventType,
                           TargetService targetService,
                           Object payload) {
        JsonNode payloadJson = objectMapper.valueToTree(payload);

        SagaLog sagaLog = SagaLog.builder()
                .aggregateId(aggregateId)
                .eventType(eventType)
                .targetService(targetService)
                .status(SagaStatus.PENDING)
                .payload(payloadJson)
                .retryCount(0)
                .maxRetries(3)
                .createdAt(LocalDateTime.now())
                .build();

        sagaLog = sagaLogRepository.save(sagaLog);

        log.info("Saga 이벤트 발행 - aggregateId: {}, eventType: {}, targetService: {}",
                aggregateId, eventType, targetService);

        return sagaLog;
    }

    /**
     * 이벤트 처리 완료
     */
    @Transactional
    public void markCompleted(SagaLog sagaLog) {
        sagaLog.markCompleted();
        sagaLogRepository.save(sagaLog);

        log.info("Saga 이벤트 완료 - id: {}, aggregateId: {}, eventType: {}",
                sagaLog.getId(), sagaLog.getAggregateId(), sagaLog.getEventType());
    }

    /**
     * 재시도 예약 (지수 백오프)
     * 30초 → 60초 → 120초
     */
    @Transactional
    public void scheduleRetry(SagaLog sagaLog, String errorMessage) {
        if (!sagaLog.canRetry()) {
            sagaLog.markFailed(errorMessage);
            sagaLogRepository.save(sagaLog);

            log.error("Saga 이벤트 최대 재시도 초과 - id: {}, aggregateId: {}, eventType: {}",
                    sagaLog.getId(), sagaLog.getAggregateId(), sagaLog.getEventType());
            return;
        }

        int delaySeconds = calculateBackoffDelay(sagaLog.getRetryCount());
        LocalDateTime nextRetryAt = LocalDateTime.now().plusSeconds(delaySeconds);

        sagaLog.scheduleRetry(nextRetryAt, errorMessage);
        sagaLogRepository.save(sagaLog);

        log.warn("Saga 이벤트 재시도 예약 - id: {}, retryCount: {}, nextRetryAt: {}",
                sagaLog.getId(), sagaLog.getRetryCount(), nextRetryAt);
    }

    /**
     * 지수 백오프 지연 시간 계산
     */
    private int calculateBackoffDelay(int retryCount) {
        // 2^retryCount * BASE_RETRY_DELAY_SECONDS
        // retryCount=0 → 30초, retryCount=1 → 60초, retryCount=2 → 120초
        return (int) Math.pow(2, retryCount) * BASE_RETRY_DELAY_SECONDS;
    }

    /**
     * 처리 대기 중인 이벤트 조회 (비관적 락)
     */
    @Transactional
    public List<SagaLog> findPendingEventsForProcessing() {
        return sagaLogRepository.findPendingEventsForProcessing(
                SagaStatus.PENDING,
                LocalDateTime.now()
        );
    }

    /**
     * 오래된 이벤트 조회 (5분 이상)
     */
    @Transactional(readOnly = true)
    public List<SagaLog> findStaleEvents() {
        return sagaLogRepository.findStaleEvents(
                SagaStatus.PENDING,
                LocalDateTime.now().minusMinutes(5)
        );
    }

    /**
     * 실패한 자금 캡처 이벤트 조회
     */
    @Transactional(readOnly = true)
    public List<SagaLog> findFailedFundsCaptureEvents() {
        return sagaLogRepository.findFailedEventsByType(
                SagaStatus.FAILED,
                SagaEventType.FUNDS_CAPTURE
        );
    }

    /**
     * 처리중 상태로 변경
     */
    @Transactional
    public void markProcessing(SagaLog sagaLog) {
        sagaLog.markProcessing();
        sagaLogRepository.save(sagaLog);
    }

    /**
     * 집계 ID로 이벤트 조회
     */
    @Transactional(readOnly = true)
    public List<SagaLog> findByAggregateId(String aggregateId) {
        return sagaLogRepository.findByAggregateId(aggregateId);
    }
}
