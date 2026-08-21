package com.ssafy.keeping.domain.charge.service;

import com.ssafy.keeping.domain.charge.dto.ConfirmPrepareResult;
import com.ssafy.keeping.domain.charge.dto.request.PrepaymentConfirmRequest;
import com.ssafy.keeping.domain.charge.dto.request.PrepaymentReserveRequest;
import com.ssafy.keeping.domain.charge.dto.response.PrepaymentReserveResponse;
import com.ssafy.keeping.domain.charge.dto.response.PrepaymentResponseDto;
import com.ssafy.keeping.domain.charge.model.PaymentReservation;
import com.ssafy.keeping.domain.charge.repository.PaymentReservationRepository;
import com.ssafy.keeping.domain.idempotency.model.IdempotentResult;
import com.ssafy.keeping.domain.payment.toss.TossPaymentClient;
import com.ssafy.keeping.domain.payment.toss.dto.TossPaymentConfirmRequest;
import com.ssafy.keeping.domain.payment.toss.dto.TossPaymentConfirmResponse;
import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.store.repository.StoreRepository;
import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.user.customer.repository.CustomerRepository;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 선결제(충전) 서비스 — Saga 오케스트레이터.
 *
 * <p>Phase A (PrepaymentConfirmService, @Transactional): 예약 검증 + 지갑 확보, 커밋 → 락 해제 토스 confirm (트랜잭션
 * 밖): 외부 PG 호출 Phase B (PrepaymentConfirmService, @Transactional): 적립(Transaction+Lot+balance) +
 * markAsCompleted 보상 (PrepaymentCompensationService, REQUIRES_NEW): 토스 cancel + 예약 FAILED (독립 커밋)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrepaymentService {

  private final TossPaymentClient tossPaymentClient;
  private final CustomerRepository customerRepository;
  private final StoreRepository storeRepository;
  private final PaymentReservationRepository paymentReservationRepository;
  private final PrepaymentConfirmService prepaymentConfirmService;
  private final PrepaymentCompensationService prepaymentCompensationService;

  private static final int RESERVATION_EXPIRES_MINUTES = 10;

  /** [1단계] 결제 예약 생성 */
  @Transactional
  public PrepaymentReserveResponse reservePayment(
      Long storeId, Long customerId, PrepaymentReserveRequest request) {

    Customer customer =
        customerRepository
            .findById(customerId)
            .orElseThrow(() -> new CustomException(ErrorCode.CUSTOMER_NOT_FOUND));

    Store store =
        storeRepository
            .findById(storeId)
            .orElseThrow(() -> new CustomException(ErrorCode.STORE_NOT_FOUND));

    String orderId = "ORDER_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();

    String orderName =
        request.getOrderName() != null
            ? request.getOrderName()
            : String.format("%s %,d원 충전", store.getStoreName(), request.getAmount());

    LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(RESERVATION_EXPIRES_MINUTES);

    PaymentReservation reservation =
        PaymentReservation.builder()
            .orderId(orderId)
            .customer(customer)
            .store(store)
            .amount(request.getAmount())
            .orderName(orderName)
            .status(PaymentReservation.ReservationStatus.PENDING)
            .expiresAt(expiresAt)
            .build();

    reservation = paymentReservationRepository.save(reservation);

    return PrepaymentReserveResponse.builder()
        .reservationId(reservation.getReservationId())
        .orderId(orderId)
        .amount(request.getAmount())
        .orderName(orderName)
        .expiresAt(expiresAt)
        .storeName(store.getStoreName())
        .build();
  }

  /** [3단계] 결제 승인 — Saga (비-트랜잭션 오케스트레이터). */
  public IdempotentResult<PrepaymentResponseDto> confirmPayment(
      Long storeId, Long customerId, PrepaymentConfirmRequest request) {

    // ── Phase A: 예약 검증 + 지갑 확보 (TX, 커밋 → 락 해제) ──
    ConfirmPrepareResult prepareResult =
        prepaymentConfirmService.prepareConfirm(storeId, customerId, request);

    // 멱등성: 이미 COMPLETED면 즉시 replay 반환
    if (prepareResult.isReplay()) {
      return prepareResult.getReplayResponse();
    }

    // ── 토스 confirm (TX 밖, 외부 호출) ──
    TossPaymentConfirmRequest tossRequest =
        TossPaymentConfirmRequest.builder()
            .paymentKey(request.getPaymentKey())
            .orderId(request.getOrderId())
            .amount(request.getAmount())
            .build();

    TossPaymentConfirmResponse tossResponse;
    try {
      tossResponse = tossPaymentClient.confirmPayment(tossRequest);
    } catch (Exception e) {
      log.error("[충전] 토스 confirm 호출 예외 - orderId: {}", request.getOrderId(), e);
      prepaymentCompensationService.markReservationFailed(prepareResult.getReservationId());
      throw e;
    }

    if (!tossResponse.isSuccess() && !isAlreadyProcessed(tossResponse)) {
      // 토스 confirm 실패 → 예약 FAILED (독립 tx) + throw
      log.error(
          "[충전] 토스 confirm 실패 - code: {}, message: {}",
          tossResponse.getCode(),
          tossResponse.getMessage());
      prepaymentCompensationService.markReservationFailed(prepareResult.getReservationId());
      throw new CustomException(ErrorCode.PAYMENT_CONFIRM_FAILED);
    }

    log.info("[충전] 토스 confirm 성공 - paymentKey: {}", tossResponse.getPaymentKey());

    // ── Phase B: 적립 (TX, 재-비관락 + COMPLETED 재확인) ──
    try {
      PrepaymentResponseDto response =
          prepaymentConfirmService.creditPoints(
              request.getOrderId(),
              request.getPaymentKey(),
              prepareResult.getWallet(),
              prepareResult.getStore(),
              prepareResult.getAmount(),
              tossResponse);

      return IdempotentResult.created(response);

    } catch (Exception e) {
      // Phase B(적립) 실패 → 보상: 토스 cancel + 예약 FAILED (REQUIRES_NEW, 독립 커밋)
      log.error("[충전] Phase B 적립 실패, 보상 시작 - paymentKey: {}", tossResponse.getPaymentKey(), e);

      prepaymentCompensationService.compensate(
          prepareResult.getReservationId(), tossResponse.getPaymentKey(), "적립 실패로 인한 자동 취소");

      throw new CustomException(ErrorCode.PAYMENT_CONFIRM_FAILED);
    }
  }

  /** "이미 처리됨" 판정 — 동시 confirm 시 실제 성공 건을 FAILED로 마킹하는 것 방지. C4의 ALREADY_CANCELED 처리와 동일 원리. */
  private boolean isAlreadyProcessed(TossPaymentConfirmResponse response) {
    String code = response.getCode();
    return code != null
        && (code.contains("ALREADY_PROCESSED") || code.contains("ALREADY_APPROVED"));
  }
}
