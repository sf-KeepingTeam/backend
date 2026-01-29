package com.ssafy.keeping.domain.charge.service;

import com.ssafy.keeping.domain.charge.dto.request.PrepaymentConfirmRequest;
import com.ssafy.keeping.domain.charge.dto.request.PrepaymentReserveRequest;
import com.ssafy.keeping.domain.charge.dto.response.PrepaymentPrepareResult;
import com.ssafy.keeping.domain.charge.dto.response.PrepaymentReserveResponse;
import com.ssafy.keeping.domain.charge.dto.response.PrepaymentResponseDto;
import com.ssafy.keeping.domain.charge.model.ChargeBonus;
import com.ssafy.keeping.domain.charge.model.PaymentReservation;
import com.ssafy.keeping.domain.charge.repository.PaymentReservationRepository;
import com.ssafy.keeping.domain.idempotency.model.IdempotentResult;
import com.ssafy.keeping.domain.payment.toss.TossPaymentClient;
import com.ssafy.keeping.domain.payment.toss.dto.TossCancelRequest;
import com.ssafy.keeping.domain.payment.toss.dto.TossCancelResponse;
import com.ssafy.keeping.domain.payment.toss.dto.TossPaymentConfirmRequest;
import com.ssafy.keeping.domain.payment.toss.dto.TossPaymentConfirmResponse;
import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.user.customer.repository.CustomerRepository;
import com.ssafy.keeping.domain.payment.transactions.constant.TransactionType;
import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.store.repository.StoreRepository;
import com.ssafy.keeping.domain.payment.transactions.model.Transaction;
import com.ssafy.keeping.domain.payment.transactions.repository.TransactionRepository;
import com.ssafy.keeping.domain.wallet.constant.LotSourceType;
import com.ssafy.keeping.domain.wallet.constant.WalletType;
import com.ssafy.keeping.domain.wallet.constant.LotStatus;
import com.ssafy.keeping.domain.wallet.model.Wallet;
import com.ssafy.keeping.domain.wallet.model.WalletStoreBalance;
import com.ssafy.keeping.domain.wallet.model.WalletStoreLot;
import com.ssafy.keeping.domain.wallet.repository.WalletRepository;
import com.ssafy.keeping.domain.wallet.repository.WalletStoreBalanceRepository;
import com.ssafy.keeping.domain.wallet.repository.WalletStoreLotRepository;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 선결제(충전) 서비스 - 3-Phase 패턴 적용
 *
 * Phase 1 (prepareConfirm): 트랜잭션 내에서 예약 검증 및 상태 확인
 * Phase 2 (executeConfirm): 트랜잭션 외부에서 토스 API 호출
 * Phase 3 (completePayment/compensatePayment): 결과에 따라 DB 업데이트 또는 보상
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrepaymentService {

    // === 외부 서비스 ===
    private final TossPaymentClient tossPaymentClient;

    // === Charge 도메인 내부 ===
    private final ChargeBonusService chargeBonusService;
    private final PaymentReservationRepository paymentReservationRepository;

    // === 외부 도메인 의존성 (MSA 전환 시 API 호출로 대체) ===
    // User 도메인: Pattern 1 (호출자 검증) - Controller에서 existsById 검증
    private final CustomerRepository customerRepository;
    // Store 도메인: Pattern 3 (데이터 복제) - PaymentReservation에 storeName 스냅샷 이미 저장
    private final StoreRepository storeRepository;
    // Wallet 도메인: WalletService API 호출로 전환 (createLot, updateBalance 등)
    private final WalletRepository walletRepository;
    private final WalletStoreLotRepository walletStoreLotRepository;
    private final WalletStoreBalanceRepository walletStoreBalanceRepository;
    // Payment 도메인: 동일 Bounded Context로 유지
    private final TransactionRepository transactionRepository;

    private static final int RESERVATION_EXPIRES_MINUTES = 10;

    /**
     * [1단계] 결제 예약 생성
     * 서버에서 금액을 먼저 확정하여 금액 변조 방지
     * 점주가 설정한 ChargeBonus 금액만 허용
     */
    @Transactional
    public PrepaymentReserveResponse reservePayment(
            Long storeId,
            Long customerId,
            PrepaymentReserveRequest request) {

        log.info("[예약] 시작 - 가게ID: {}, 고객ID: {}, 금액: {}원",
                storeId, customerId, request.getAmount());

        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException(ErrorCode.STORE_NOT_FOUND));

        // 점주가 설정한 충전 금액인지 검증 및 보너스 정보 조회
        ChargeBonus chargeBonus = chargeBonusService.findChargeBonusByAmount(storeId, request.getAmount())
                .orElseThrow(() -> {
                    log.warn("[예약] 유효하지 않은 충전 금액 - 가게ID: {}, 요청 금액: {}원", storeId, request.getAmount());
                    return new CustomException(ErrorCode.INVALID_CHARGE_AMOUNT);
                });

        // 보너스 계산 (예약 시점 확정)
        int bonusPercentage = chargeBonus.getBonusPercentage();
        long bonusAmount = (request.getAmount() * bonusPercentage) / 100;
        long expectedTotalPoints = request.getAmount() + bonusAmount;

        log.info("[예약] 보너스 계산 - 결제금액: {}원, 보너스: {}% ({}원), 총포인트: {}P",
                request.getAmount(), bonusPercentage, bonusAmount, expectedTotalPoints);

        String orderId = "ORDER_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();

        String orderName = request.getOrderName() != null
                ? request.getOrderName()
                : String.format("%s %,d원 충전", store.getStoreName(), request.getAmount());

        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(RESERVATION_EXPIRES_MINUTES);

        PaymentReservation reservation = PaymentReservation.builder()
                .orderId(orderId)
                .customerId(customerId)
                .storeId(storeId)
                .amount(request.getAmount())
                .bonusPercentage(bonusPercentage)
                .bonusAmount(bonusAmount)
                .expectedTotalPoints(expectedTotalPoints)
                .orderName(orderName)
                .status(PaymentReservation.ReservationStatus.PENDING)
                .expiresAt(expiresAt)
                .build();

        reservation = paymentReservationRepository.save(reservation);

        log.info("[예약] 생성 완료 - 예약ID: {}, orderId: {}, 총포인트: {}P, 만료: {}",
                reservation.getReservationId(), orderId, expectedTotalPoints, expiresAt);

        return PrepaymentReserveResponse.builder()
                .reservationId(reservation.getReservationId())
                .orderId(orderId)
                .amount(request.getAmount())
                .bonusPercentage(bonusPercentage)
                .bonusAmount(bonusAmount)
                .expectedTotalPoints(expectedTotalPoints)
                .orderName(orderName)
                .expiresAt(expiresAt)
                .storeName(store.getStoreName())
                .build();
    }

    /**
     * [3단계] 결제 승인 - 3-Phase 패턴 적용
     */
    public IdempotentResult<PrepaymentResponseDto> confirmPayment(
            Long storeId,
            Long customerId,
            PrepaymentConfirmRequest request) {

        log.info("[승인] 3-Phase 시작 - orderId: {}", request.getOrderId());

        // Phase 1: 검증 및 준비 (트랜잭션 내)
        PrepaymentPrepareResult prepareResult = prepareConfirm(storeId, customerId, request);

        // 이미 처리된 경우 (멱등성)
        if (prepareResult == null) {
            return handleAlreadyCompleted(request.getOrderId());
        }

        // Phase 2 & 3: 토스 API 호출 및 완료/보상
        return executeConfirm(prepareResult, request);
    }

    /**
     * Phase 1: 결제 승인 준비
     * - 예약 검증
     * - 멱등성 확인
     * - 지갑 준비
     */
    @Transactional
    public PrepaymentPrepareResult prepareConfirm(
            Long storeId,
            Long customerId,
            PrepaymentConfirmRequest request) {

        log.info("[승인-Phase1] 준비 시작 - orderId: {}", request.getOrderId());

        // 1. 예약 조회 (비관적 락)
        PaymentReservation reservation = paymentReservationRepository
                .findByOrderIdWithLock(request.getOrderId())
                .orElseThrow(() -> new CustomException(ErrorCode.TRANSACTION_NOT_FOUND));

        // 2. 소유권 검증
        if (!reservation.getCustomerId().equals(customerId)) {
            log.error("[승인-Phase1] 권한 없음 - 예약 고객: {}, 요청 고객: {}",
                    reservation.getCustomerId(), customerId);
            throw new CustomException(ErrorCode.UNAUTHORIZED_ACCESS);
        }

        // 3. 가게 검증
        if (!reservation.getStoreId().equals(storeId)) {
            log.error("[승인-Phase1] 가게 불일치 - 예약 가게: {}, 요청 가게: {}",
                    reservation.getStoreId(), storeId);
            throw new CustomException(ErrorCode.INVALID_REQUEST);
        }

        // 4. 금액 검증
        if (!reservation.getAmount().equals(request.getAmount())) {
            log.error("[승인-Phase1] 금액 변조 감지 - 예약 금액: {}, 요청 금액: {}",
                    reservation.getAmount(), request.getAmount());
            throw new CustomException(ErrorCode.INVALID_REQUEST);
        }

        // 5. 만료 확인
        if (reservation.isExpired()) {
            log.error("[승인-Phase1] 예약 만료 - orderId: {}", request.getOrderId());
            reservation.markAsExpired();
            throw new CustomException(ErrorCode.INVALID_REQUEST);
        }

        // 6. 멱등성 확인 - 이미 완료된 경우
        if (reservation.getStatus() == PaymentReservation.ReservationStatus.COMPLETED) {
            log.info("[승인-Phase1] 이미 처리됨 - orderId: {}", request.getOrderId());
            return null; // 멱등성 처리를 위해 null 반환
        }

        // 7. 지갑 조회 또는 생성
        Wallet wallet = findOrCreateIndividualWallet(customerId);

        // 8. 가게 정보 조회
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException(ErrorCode.STORE_NOT_FOUND));

        log.info("[승인-Phase1] 준비 완료 - 예약ID: {}, 지갑ID: {}",
                reservation.getReservationId(), wallet.getWalletId());

        return PrepaymentPrepareResult.builder()
                .reservationId(reservation.getReservationId())
                .orderId(request.getOrderId())
                .paymentKey(request.getPaymentKey())
                .customerId(customerId)
                .storeId(storeId)
                .storeName(store.getStoreName())
                .walletId(wallet.getWalletId())
                .amount(request.getAmount())
                .build();
    }

    /**
     * Phase 2: 토스 API 호출 및 Phase 3 실행
     */
    public IdempotentResult<PrepaymentResponseDto> executeConfirm(
            PrepaymentPrepareResult prepared,
            PrepaymentConfirmRequest request) {

        log.info("[승인-Phase2] 토스 API 호출 시작 - orderId: {}", prepared.orderId());

        TossPaymentConfirmRequest tossRequest = TossPaymentConfirmRequest.builder()
                .paymentKey(request.getPaymentKey())
                .orderId(request.getOrderId())
                .amount(request.getAmount())
                .build();

        TossPaymentConfirmResponse tossResponse;
        try {
            tossResponse = tossPaymentClient.confirmPayment(tossRequest);
        } catch (Exception e) {
            log.error("[승인-Phase2] 토스 API 호출 실패", e);
            markReservationAsFailed(prepared.reservationId());
            throw new CustomException(ErrorCode.PAYMENT_CONFIRM_FAILED);
        }

        if (!tossResponse.isSuccess()) {
            log.error("[승인-Phase2] 토스 결제 실패 - code: {}, message: {}",
                    tossResponse.getCode(), tossResponse.getMessage());
            markReservationAsFailed(prepared.reservationId());
            throw new CustomException(ErrorCode.PAYMENT_CONFIRM_FAILED);
        }

        log.info("[승인-Phase2] 토스 결제 성공 - paymentKey: {}", tossResponse.getPaymentKey());

        // Phase 3: DB 저장
        try {
            PrepaymentResponseDto response = completePayment(prepared, tossResponse);
            return IdempotentResult.created(response);
        } catch (Exception e) {
            log.error("[승인-Phase3] DB 저장 실패, 보상 트랜잭션 시작", e);
            compensatePayment(prepared, request.getPaymentKey());
            throw new CustomException(ErrorCode.PAYMENT_CONFIRM_FAILED);
        }
    }

    /**
     * Phase 3 (성공): 결제 완료 처리
     * 예약 시점에 확정된 보너스 정보를 사용 (점주가 중간에 보너스 변경해도 예약 시점 보너스 적용)
     */
    @Transactional
    public PrepaymentResponseDto completePayment(
            PrepaymentPrepareResult prepared,
            TossPaymentConfirmResponse tossResponse) {

        log.info("[승인-Phase3] 완료 처리 시작 - 예약ID: {}", prepared.reservationId());

        LocalDateTime now = LocalDateTime.now();

        // 1. 예약 조회 (보너스 정보 포함)
        PaymentReservation reservation = paymentReservationRepository.findById(prepared.reservationId())
                .orElseThrow(() -> new CustomException(ErrorCode.TRANSACTION_NOT_FOUND));

        // 2. 지갑 조회
        Wallet wallet = walletRepository.findById(prepared.walletId())
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));

        // 3. 가게 조회
        Store store = storeRepository.findById(prepared.storeId())
                .orElseThrow(() -> new CustomException(ErrorCode.STORE_NOT_FOUND));

        // 4. 예약 시점에 확정된 보너스 정보 사용
        int bonusPercentage = reservation.getBonusPercentage();
        long bonusAmount = reservation.getBonusAmount();
        long totalPoints = reservation.getExpectedTotalPoints();

        log.info("[보너스] 예약 시점 확정값 사용 - {}원 × {}% = {}원, 총포인트: {}P",
                prepared.amount(), bonusPercentage, bonusAmount, totalPoints);

        // 5. Transaction 생성
        Transaction transaction = Transaction.builder()
                .walletId(wallet.getWalletId())
                .customerId(wallet.getCustomerId())
                .storeId(store.getStoreId())
                .transactionType(TransactionType.CHARGE)
                .amount(prepared.amount())
                .transactionUniqueNo(tossResponse.getPaymentKey())
                .build();
        transaction = transactionRepository.save(transaction);

        // 6. WalletStoreLot 생성
        LocalDateTime expiredAt = now.plusYears(1);
        WalletStoreLot lot = WalletStoreLot.builder()
                .wallet(wallet)
                .storeId(store.getStoreId())
                .amountTotal(totalPoints)
                .amountRemaining(totalPoints)
                .acquiredAt(now)
                .expiredAt(expiredAt)
                .sourceType(LotSourceType.CHARGE)
                .lotStatus(LotStatus.ACTIVE)
                .originChargeTransactionId(transaction.getTransactionId())
                .build();
        walletStoreLotRepository.save(lot);

        // 7. WalletStoreBalance 업데이트
        WalletStoreBalance balance = walletStoreBalanceRepository
                .findByWallet_WalletIdAndStoreId(wallet.getWalletId(), store.getStoreId())
                .orElseGet(() -> WalletStoreBalance.builder()
                        .wallet(wallet)
                        .storeId(store.getStoreId())
                        .storeNameSnapshot(store.getStoreName())
                        .balance(0L)
                        .build());

        balance.addBalance(totalPoints);
        walletStoreBalanceRepository.save(balance);

        // 8. 예약 완료 처리
        reservation.markAsCompleted(tossResponse.getPaymentKey());

        log.info("[승인-Phase3] 완료 - 고객ID: {}, 결제금액: {}원, 총포인트: {}P",
                wallet.getCustomerId(), prepared.amount(), totalPoints);

        return PrepaymentResponseDto.builder()
                .transactionId(transaction.getTransactionId())
                .transactionUniqueNo(tossResponse.getPaymentKey())
                .storeId(store.getStoreId())
                .storeName(store.getStoreName())
                .paymentAmount(prepared.amount())
                .bonusPercentage(bonusPercentage)
                .bonusAmount(bonusAmount)
                .totalPoints(totalPoints)
                .transactionTime(transaction.getCreatedAt())
                .remainingBalance(balance.getBalance())
                .build();
    }

    /**
     * Phase 3 (실패): 보상 트랜잭션 - 토스 결제 취소
     */
    @Transactional
    public void compensatePayment(PrepaymentPrepareResult prepared, String paymentKey) {
        log.warn("[보상] 토스 결제 취소 시작 - paymentKey: {}", paymentKey);

        try {
            TossCancelRequest tossCancelRequest = TossCancelRequest.builder()
                    .cancelReason("시스템 오류로 인한 자동 취소")
                    .build();

            TossCancelResponse cancelResponse = tossPaymentClient.cancelPayment(paymentKey, tossCancelRequest);

            if (cancelResponse.isSuccess()) {
                log.info("[보상] 토스 결제 취소 성공 - paymentKey: {}", paymentKey);
            } else {
                log.error("[보상] 토스 결제 취소 실패 - paymentKey: {}, code: {}, message: {}",
                        paymentKey, cancelResponse.getCode(), cancelResponse.getMessage());
            }
        } catch (Exception e) {
            log.error("[보상] 토스 결제 취소 중 오류 - 수동 처리 필요! paymentKey: {}", paymentKey, e);
        }

        // 예약 상태 업데이트
        markReservationAsFailed(prepared.reservationId());
    }

    /**
     * 예약 실패 상태로 마킹
     */
    @Transactional
    public void markReservationAsFailed(Long reservationId) {
        PaymentReservation reservation = paymentReservationRepository.findById(reservationId)
                .orElse(null);
        if (reservation != null) {
            reservation.markAsFailed();
        }
    }

    /**
     * 이미 완료된 결제 처리 (멱등성)
     */
    @Transactional(readOnly = true)
    public IdempotentResult<PrepaymentResponseDto> handleAlreadyCompleted(String orderId) {
        PaymentReservation reservation = paymentReservationRepository.findByOrderId(orderId)
                .orElseThrow(() -> new CustomException(ErrorCode.TRANSACTION_NOT_FOUND));

        Transaction existingTransaction = transactionRepository
                .findByTransactionUniqueNo(reservation.getPaymentKey())
                .orElseThrow(() -> new CustomException(ErrorCode.TRANSACTION_NOT_FOUND));

        WalletStoreBalance balance = walletStoreBalanceRepository
                .findByWallet_WalletIdAndStoreId(existingTransaction.getWalletId(), existingTransaction.getStoreId())
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));

        WalletStoreLot existingLot = walletStoreLotRepository
                .findByOriginChargeTransactionId(existingTransaction.getTransactionId())
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));

        Store existingStore = storeRepository.findById(existingTransaction.getStoreId())
                .orElseThrow(() -> new CustomException(ErrorCode.STORE_NOT_FOUND));

        long paymentAmount = existingTransaction.getAmount();
        long totalPoints = existingLot.getAmountTotal();
        long bonusAmount = totalPoints - paymentAmount;
        int bonusPercentage = bonusAmount > 0 ? (int)((bonusAmount * 100) / paymentAmount) : 0;

        PrepaymentResponseDto response = PrepaymentResponseDto.builder()
                .transactionId(existingTransaction.getTransactionId())
                .transactionUniqueNo(existingTransaction.getTransactionUniqueNo())
                .storeId(existingStore.getStoreId())
                .storeName(existingStore.getStoreName())
                .paymentAmount(paymentAmount)
                .bonusPercentage(bonusPercentage)
                .bonusAmount(bonusAmount)
                .totalPoints(totalPoints)
                .transactionTime(existingTransaction.getCreatedAt())
                .remainingBalance(balance.getBalance())
                .build();

        return IdempotentResult.okReplay(response);
    }

    /**
     * 개인 지갑 조회 또는 생성
     */
    private Wallet findOrCreateIndividualWallet(Long customerId) {
        return walletRepository.findByCustomerIdAndWalletType(customerId, WalletType.INDIVIDUAL)
                .orElseGet(() -> {
                    Wallet newWallet = Wallet.builder()
                            .customerId(customerId)
                            .walletType(WalletType.INDIVIDUAL)
                            .build();
                    return walletRepository.save(newWallet);
                });
    }
}
