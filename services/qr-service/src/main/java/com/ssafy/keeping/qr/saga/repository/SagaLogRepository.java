package com.ssafy.keeping.qr.saga.repository;

import com.ssafy.keeping.qr.saga.constant.SagaEventType;
import com.ssafy.keeping.qr.saga.constant.SagaStatus;
import com.ssafy.keeping.qr.saga.model.SagaLog;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SagaLogRepository extends JpaRepository<SagaLog, Long> {

    /**
     * 처리 대기 중인 이벤트 조회 (비관적 락)
     * - PENDING 상태
     * - nextRetryAt이 null이거나 현재 시간 이전
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM SagaLog s " +
            "WHERE s.status = :status " +
            "AND (s.nextRetryAt IS NULL OR s.nextRetryAt <= :now) " +
            "ORDER BY s.createdAt ASC")
    List<SagaLog> findPendingEventsForProcessing(
            @Param("status") SagaStatus status,
            @Param("now") LocalDateTime now
    );

    /**
     * 오래된 PENDING 이벤트 조회 (5분 이상 처리되지 않음)
     */
    @Query("SELECT s FROM SagaLog s " +
            "WHERE s.status = :status " +
            "AND s.createdAt < :threshold")
    List<SagaLog> findStaleEvents(
            @Param("status") SagaStatus status,
            @Param("threshold") LocalDateTime threshold
    );

    /**
     * 실패한 자금 캡처 이벤트 조회 (보상 처리 대상)
     */
    @Query("SELECT s FROM SagaLog s " +
            "WHERE s.status = :status " +
            "AND s.eventType = :eventType")
    List<SagaLog> findFailedEventsByType(
            @Param("status") SagaStatus status,
            @Param("eventType") SagaEventType eventType
    );

    /**
     * 집계 ID로 이벤트 조회
     */
    List<SagaLog> findByAggregateId(String aggregateId);

    /**
     * 집계 ID와 이벤트 타입으로 조회
     */
    List<SagaLog> findByAggregateIdAndEventType(String aggregateId, SagaEventType eventType);
}
