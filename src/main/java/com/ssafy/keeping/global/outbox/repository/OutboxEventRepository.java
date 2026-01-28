package com.ssafy.keeping.global.outbox.repository;

import com.ssafy.keeping.global.outbox.model.OutboxEvent;
import com.ssafy.keeping.global.outbox.model.OutboxStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * PENDING 상태의 이벤트 조회 (스케줄러용)
     * 생성 시간 순으로 정렬하여 순차 처리
     */
    @Query("""
        SELECT e FROM OutboxEvent e
        WHERE e.status = :status
        ORDER BY e.createdAt ASC
        """)
    List<OutboxEvent> findByStatus(@Param("status") OutboxStatus status, Pageable pageable);

    /**
     * PENDING 상태의 이벤트 조회 (비관적 락, 스케줄러용)
     * 동시성 문제 방지를 위해 락 적용
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT e FROM OutboxEvent e
        WHERE e.status = :status
        AND e.retryCount < :maxRetry
        ORDER BY e.createdAt ASC
        """)
    List<OutboxEvent> findPendingEventsWithLock(
        @Param("status") OutboxStatus status,
        @Param("maxRetry") int maxRetry,
        Pageable pageable
    );

    /**
     * 특정 집합체의 이벤트 조회
     */
    List<OutboxEvent> findByAggregateTypeAndAggregateId(String aggregateType, String aggregateId);

    /**
     * 특정 기간 동안의 FAILED 이벤트 조회
     */
    @Query("""
        SELECT e FROM OutboxEvent e
        WHERE e.status = :status
        AND e.createdAt >= :since
        ORDER BY e.createdAt DESC
        """)
    List<OutboxEvent> findFailedEventsSince(
        @Param("status") OutboxStatus status,
        @Param("since") LocalDateTime since
    );

    /**
     * 오래된 PUBLISHED 이벤트 삭제 (정리용)
     */
    @Modifying
    @Query("""
        DELETE FROM OutboxEvent e
        WHERE e.status = :status
        AND e.publishedAt < :before
        """)
    int deletePublishedEventsBefore(
        @Param("status") OutboxStatus status,
        @Param("before") LocalDateTime before
    );

    /**
     * 상태별 이벤트 수 조회 (모니터링용)
     */
    long countByStatus(OutboxStatus status);

    /**
     * 특정 이벤트 타입의 PENDING 이벤트 조회
     */
    @Query("""
        SELECT e FROM OutboxEvent e
        WHERE e.status = :status
        AND e.eventType = :eventType
        ORDER BY e.createdAt ASC
        """)
    List<OutboxEvent> findByStatusAndEventType(
        @Param("status") OutboxStatus status,
        @Param("eventType") String eventType,
        Pageable pageable
    );
}
