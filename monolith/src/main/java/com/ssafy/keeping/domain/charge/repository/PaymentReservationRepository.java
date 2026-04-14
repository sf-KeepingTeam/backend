package com.ssafy.keeping.domain.charge.repository;

import com.ssafy.keeping.domain.charge.model.PaymentReservation;
import com.ssafy.keeping.domain.charge.model.PaymentReservation.ReservationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentReservationRepository extends JpaRepository<PaymentReservation, Long> {

    /**
     * orderId로 예약 조회
     */
    Optional<PaymentReservation> findByOrderId(String orderId);

    /**
     * orderId로 예약 조회 (비관적 락)
     * 동시성 문제 해결: 같은 orderId로 동시에 여러 요청이 와도 순차 처리
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT pr FROM PaymentReservation pr WHERE pr.orderId = :orderId")
    Optional<PaymentReservation> findByOrderIdWithLock(@Param("orderId") String orderId);

    /**
     * orderId 존재 여부 확인 (중복 방지)
     */
    boolean existsByOrderId(String orderId);

    /**
     * 고객의 특정 상태 예약 목록 조회
     */
    List<PaymentReservation> findByCustomerCustomerIdAndStatus(Long customerId, ReservationStatus status);

    /**
     * 만료된 예약 조회 (스케줄러용)
     */
    @Query("""
        SELECT pr FROM PaymentReservation pr
        WHERE pr.status = :status
        AND pr.expiresAt < :now
        """)
    List<PaymentReservation> findExpiredReservations(
        @Param("status") ReservationStatus status,
        @Param("now") LocalDateTime now
    );

    /**
     * 특정 기간 동안의 예약 통계 (선택)
     */
    @Query("""
        SELECT COUNT(pr) FROM PaymentReservation pr
        WHERE pr.customer.customerId = :customerId
        AND pr.status = :status
        AND pr.createdAt BETWEEN :startDate AND :endDate
        """)
    Long countByCustomerAndStatusAndPeriod(
        @Param("customerId") Long customerId,
        @Param("status") ReservationStatus status,
        @Param("startDate") LocalDateTime startDate,
        @Param("endDate") LocalDateTime endDate
    );
}
