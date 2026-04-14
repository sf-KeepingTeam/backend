package com.ssafy.keeping.domain.charge.service;

import com.ssafy.keeping.domain.charge.model.PaymentReservation;
import com.ssafy.keeping.domain.charge.repository.PaymentReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 결제 예약 스케줄러
 * 만료된 예약을 주기적으로 정리
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentReservationScheduler {

    private final PaymentReservationRepository paymentReservationRepository;

    /**
     * 만료된 예약 정리
     * 5분마다 실행
     */
    @Scheduled(cron = "0 */5 * * * *") // 5분마다
    @Transactional
    public void cleanupExpiredReservations() {
        log.info("[스케줄러] 만료된 예약 정리 시작");

        LocalDateTime now = LocalDateTime.now();

        // PENDING 상태이면서 만료 시간이 지난 예약 조회
        List<PaymentReservation> expiredReservations = paymentReservationRepository
                .findExpiredReservations(PaymentReservation.ReservationStatus.PENDING, now);

        if (expiredReservations.isEmpty()) {
            log.info("[스케줄러] 만료된 예약 없음");
            return;
        }

        // 만료 처리
        expiredReservations.forEach(reservation -> {
            reservation.markAsExpired();
            log.debug("[스케줄러] 예약 만료 처리 - orderId: {}, 생성: {}, 만료: {}",
                    reservation.getOrderId(),
                    reservation.getCreatedAt(),
                    reservation.getExpiresAt());
        });

        paymentReservationRepository.saveAll(expiredReservations);

        log.info("[스케줄러] 만료된 예약 {} 건 정리 완료", expiredReservations.size());
    }

    /**
     * 오래된 예약 삭제 (선택)
     * 매일 새벽 3시에 실행
     * 30일 이상 지난 완료/만료/실패 예약 삭제
     */
    @Scheduled(cron = "0 0 3 * * *") // 매일 3시
    @Transactional
    public void deleteOldReservations() {
        log.info("[스케줄러] 오래된 예약 삭제 시작");

        LocalDateTime threshold = LocalDateTime.now().minusDays(30);

        // 30일 이상 지난 예약 삭제 (PENDING 제외)
        long deletedCount = 0;

        for (PaymentReservation.ReservationStatus status :
                List.of(
                        PaymentReservation.ReservationStatus.COMPLETED,
                        PaymentReservation.ReservationStatus.EXPIRED,
                        PaymentReservation.ReservationStatus.FAILED
                )) {

            List<PaymentReservation> oldReservations = paymentReservationRepository
                    .findExpiredReservations(status, threshold);

            if (!oldReservations.isEmpty()) {
                paymentReservationRepository.deleteAll(oldReservations);
                deletedCount += oldReservations.size();
                log.info("[스케줄러] {} 상태 예약 {} 건 삭제", status, oldReservations.size());
            }
        }

        log.info("[스케줄러] 오래된 예약 총 {} 건 삭제 완료", deletedCount);
    }
}
