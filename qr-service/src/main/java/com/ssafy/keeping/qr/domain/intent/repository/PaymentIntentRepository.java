package com.ssafy.keeping.qr.domain.intent.repository;

import com.ssafy.keeping.qr.domain.intent.constant.PaymentStatus;
import com.ssafy.keeping.qr.domain.intent.model.PaymentIntent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, Long> {

    Optional<PaymentIntent> findByPublicId(UUID publicId);

    Optional<PaymentIntent> findByIdempotencyKey(String idempotencyKey);

    Optional<PaymentIntent> findByQrTokenId(String qrTokenId);

    /**
     * 만료된 PENDING 상태 Intent 조회
     */
    @Query("SELECT pi FROM PaymentIntent pi WHERE pi.status = :status AND pi.expiresAt < :now")
    List<PaymentIntent> findExpiredIntents(
            @Param("status") PaymentStatus status,
            @Param("now") LocalDateTime now
    );

    /**
     * 배치로 만료 처리
     */
    @Modifying
    @Query("UPDATE PaymentIntent pi SET pi.status = 'EXPIRED' WHERE pi.status = 'PENDING' AND pi.expiresAt < :now")
    int expirePendingIntents(@Param("now") LocalDateTime now);

    /**
     * 복구 대상 조회: UNCERTAIN 상태이거나 만료된 PENDING 상태의 Intent
     */
    @Query("""
        SELECT pi FROM PaymentIntent pi
        WHERE (pi.status = :uncertainStatus
               OR (pi.status = :pendingStatus AND pi.expiresAt < :now))
          AND pi.createdAt > :since
        ORDER BY pi.createdAt ASC
        """)
    List<PaymentIntent> findRecoveryTargets(
            @Param("uncertainStatus") PaymentStatus uncertainStatus,
            @Param("pendingStatus") PaymentStatus pendingStatus,
            @Param("now") LocalDateTime now,
            @Param("since") LocalDateTime since
    );
}
