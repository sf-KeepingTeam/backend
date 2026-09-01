package com.ssafy.keeping.domain.charge.service;

import com.ssafy.keeping.domain.charge.model.PaymentReservation;
import com.ssafy.keeping.domain.charge.repository.PaymentReservationRepository;
import com.ssafy.keeping.domain.payment.toss.TossPaymentClient;
import com.ssafy.keeping.domain.payment.toss.dto.TossCancelRequest;
import com.ssafy.keeping.domain.payment.toss.dto.TossCancelResponse;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 충전 보상 전담 빈. REQUIRES_NEW로 독립 커밋 — 외부 트랜잭션 롤백에 먹히지 않는다. */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrepaymentCompensationService {

  private final TossPaymentClient tossPaymentClient;
  private final PaymentReservationRepository paymentReservationRepository;

  /**
   * 보상: 토스 cancel + 예약 FAILED 마킹. 독립 트랜잭션(REQUIRES_NEW)으로 외부 롤백에 영향받지 않는다. 가드: 예약이 이미 COMPLETED면
   * FAILED로 덮어쓰지 않는다.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void compensate(Long reservationId, String paymentKey, String reason) {
    log.warn(
        "[충전-보상] 시작 - reservationId: {}, paymentKey: {}, reason: {}",
        reservationId,
        paymentKey,
        reason);

    // 토스 cancel (멱등키 cancel:{paymentKey}는 RealTossPaymentClient에서 자동 적용)
    TossCancelRequest cancelRequest = TossCancelRequest.builder().cancelReason(reason).build();

    try {
      TossCancelResponse cancelResponse =
          tossPaymentClient.cancelPayment(paymentKey, cancelRequest);
      if (cancelResponse.isSuccess()) {
        log.info("[충전-보상] 토스 결제 취소 성공 - paymentKey: {}", paymentKey);
      } else {
        log.error(
            "[충전-보상] 토스 결제 취소 실패 - paymentKey: {}, code: {}, message: {}",
            paymentKey,
            cancelResponse.getCode(),
            cancelResponse.getMessage());
      }
    } catch (Exception e) {
      log.error("[충전-보상] 토스 결제 취소 예외 - paymentKey: {}, error: {}", paymentKey, e.getMessage());
    }

    // 예약 FAILED 마킹 (COMPLETED면 건드리지 않음)
    markReservationFailedGuarded(reservationId);
  }

  /** 토스 confirm 실패 시 예약만 FAILED 마킹 (독립 tx). 가드: 이미 COMPLETED면 FAILED로 덮어쓰지 않는다. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markReservationFailed(Long reservationId) {
    markReservationFailedGuarded(reservationId);
  }

  private void markReservationFailedGuarded(Long reservationId) {
    PaymentReservation reservation =
        paymentReservationRepository
            .findById(reservationId)
            .orElseThrow(() -> new CustomException(ErrorCode.TRANSACTION_NOT_FOUND));

    if (reservation.getStatus() == PaymentReservation.ReservationStatus.COMPLETED) {
      log.info("[충전-보상] 예약이 이미 COMPLETED — FAILED 덮어쓰기 방지. reservationId: {}", reservationId);
      return;
    }

    reservation.markAsFailed();
    paymentReservationRepository.save(reservation);
    log.info("[충전-보상] 예약 FAILED 마킹 완료 - reservationId: {}", reservationId);
  }
}
