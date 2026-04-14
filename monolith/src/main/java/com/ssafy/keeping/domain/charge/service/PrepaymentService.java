package com.ssafy.keeping.domain.charge.service;

import com.ssafy.keeping.domain.charge.dto.request.PrepaymentConfirmRequest;
import com.ssafy.keeping.domain.charge.dto.request.PrepaymentRequestDto;
import com.ssafy.keeping.domain.charge.dto.request.PrepaymentReserveRequest;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 선결제(충전) 서비스 - 보안 강화 버전
 * 예약 방식으로 금액 변조 방지
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class PrepaymentService {

    private final TossPaymentClient tossPaymentClient;
    private final CustomerRepository customerRepository;
    private final StoreRepository storeRepository;
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final WalletStoreLotRepository walletStoreLotRepository;
    private final WalletStoreBalanceRepository walletStoreBalanceRepository;
    private final ChargeBonusService chargeBonusService;
    private final PaymentReservationRepository paymentReservationRepository;

    // 예약 만료 시간 (분)
    private static final int RESERVATION_EXPIRES_MINUTES = 10;

    /**
     * [1단계] 결제 예약 생성
     * 서버에서 금액을 먼저 확정하여 금액 변조 방지
     */
    public PrepaymentReserveResponse reservePayment(
            Long storeId,
            Long customerId,
            PrepaymentReserveRequest request) {

//        log.info("[예약] 시작 - 가게ID: {}, 고객ID: {}, 금액: {}원",
//                storeId, customerId, request.getAmount());

        // 1. 고객 조회
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new CustomException(ErrorCode.CUSTOMER_NOT_FOUND));

        // 2. 가게 조회
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException(ErrorCode.STORE_NOT_FOUND));

        // 3. orderId 생성 (UUID v4)
        String orderId = "ORDER_" + UUID.randomUUID().toString().replace("-", "").toUpperCase();

        // 4. 주문명 생성
        String orderName = request.getOrderName() != null
                ? request.getOrderName()
                : String.format("%s %,d원 충전", store.getStoreName(), request.getAmount());

        // 5. 만료 시간 설정 (10분 후)
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(RESERVATION_EXPIRES_MINUTES);

        // 6. 예약 생성
        PaymentReservation reservation = PaymentReservation.builder()
                .orderId(orderId)
                .customer(customer)
                .store(store)
                .amount(request.getAmount())
                .orderName(orderName)
                .status(PaymentReservation.ReservationStatus.PENDING)
                .expiresAt(expiresAt)
                .build();

        reservation = paymentReservationRepository.save(reservation);

//        log.info("[예약] 생성 완료 - 예약ID: {}, orderId: {}, 만료: {}",
//                reservation.getReservationId(), orderId, expiresAt);

        // 7. 응답 생성
        return PrepaymentReserveResponse.builder()
                .reservationId(reservation.getReservationId())
                .orderId(orderId)
                .amount(request.getAmount())
                .orderName(orderName)
                .expiresAt(expiresAt)
                .storeName(store.getStoreName())
                .build();
    }

    /**
     * [3단계] 결제 승인 (기존 processPayment를 대체)
     * 예약된 금액과 비교하여 변조 방지
     * 동시성 제어: 비관적 락으로 중복 결제 방지
     */
    public IdempotentResult<PrepaymentResponseDto> confirmPayment(
            Long storeId,
            Long customerId,
            PrepaymentConfirmRequest request) {

//        log.info("[승인] 시작 - orderId: {}, 금액: {}원", request.getOrderId(), request.getAmount());

        // 1. 예약 조회 (비관적 락 - 동시성 제어)
        PaymentReservation reservation = paymentReservationRepository
                .findByOrderIdWithLock(request.getOrderId())
                .orElseThrow(() -> new CustomException(ErrorCode.TRANSACTION_NOT_FOUND));

        // 2. 소유권 검증
        if (!reservation.getCustomer().getCustomerId().equals(customerId)) {
//            log.error("[승인] 권한 없음 - 예약 고객: {}, 요청 고객: {}",
//                    reservation.getCustomer().getCustomerId(), customerId);
            throw new CustomException(ErrorCode.UNAUTHORIZED_ACCESS);
        }

        // 3. 가게 검증
        if (!reservation.getStore().getStoreId().equals(storeId)) {
//            log.error("[승인] 가게 불일치 - 예약 가게: {}, 요청 가게: {}",
//                    reservation.getStore().getStoreId(), storeId);
            throw new CustomException(ErrorCode.INVALID_REQUEST);
        }

        // 4. 금액 검증 (핵심!)
        if (!reservation.getAmount().equals(request.getAmount())) {
//            log.error("[승인] 금액 변조 감지 - 예약 금액: {}, 요청 금액: {}",
//                    reservation.getAmount(), request.getAmount());
            throw new CustomException(ErrorCode.INVALID_REQUEST);
        }

        // 5. 만료 확인
        if (reservation.isExpired()) {
//            log.error("[승인] 예약 만료 - orderId: {}, 만료 시각: {}",
//                    request.getOrderId(), reservation.getExpiresAt());
            reservation.markAsExpired();
            paymentReservationRepository.save(reservation);
            throw new CustomException(ErrorCode.INVALID_REQUEST);
        }

        // 6. 상태 확인 (멱등성)
        if (reservation.getStatus() == PaymentReservation.ReservationStatus.COMPLETED) {
//            log.info("[승인] 이미 처리됨 - orderId: {}, paymentKey: {}",
//                    request.getOrderId(), reservation.getPaymentKey());

            // 기존 거래 조회하여 반환
            Transaction existingTransaction = transactionRepository
                    .findByTransactionUniqueNo(reservation.getPaymentKey())
                    .orElseThrow(() -> new CustomException(ErrorCode.TRANSACTION_NOT_FOUND));

            WalletStoreBalance balance = walletStoreBalanceRepository
                    .findByWalletAndStore(existingTransaction.getWallet(), existingTransaction.getStore())
                    .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));

            // LOT 조회하여 실제 보너스 정보 계산
            WalletStoreLot existingLot = walletStoreLotRepository
                    .findByOriginChargeTransaction(existingTransaction)
                    .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));

            long paymentAmount = existingTransaction.getAmount();
            long totalPoints = existingLot.getAmountTotal();
            long bonusAmount = totalPoints - paymentAmount;
            int bonusPercentage = bonusAmount > 0 ? (int)((bonusAmount * 100) / paymentAmount) : 0;

            PrepaymentResponseDto response = PrepaymentResponseDto.builder()
                    .transactionId(existingTransaction.getTransactionId())
                    .transactionUniqueNo(existingTransaction.getTransactionUniqueNo())
                    .storeId(existingTransaction.getStore().getStoreId())
                    .storeName(existingTransaction.getStore().getStoreName())
                    .paymentAmount(paymentAmount)
                    .bonusPercentage(bonusPercentage)
                    .bonusAmount(bonusAmount)
                    .totalPoints(totalPoints)
                    .transactionTime(existingTransaction.getCreatedAt())
                    .remainingBalance(balance.getBalance())
                    .build();

            return IdempotentResult.okReplay(response);
        }

        // 7. 지갑 조회 또는 생성
        Wallet wallet = findOrCreateIndividualWallet(reservation.getCustomer());

        // 8. 토스페이먼츠 결제 승인 API 호출
        TossPaymentConfirmRequest tossRequest = TossPaymentConfirmRequest.builder()
                .paymentKey(request.getPaymentKey())
                .orderId(request.getOrderId())
                .amount(request.getAmount())
                .build();

        TossPaymentConfirmResponse tossResponse;
        try {
            tossResponse = tossPaymentClient.confirmPayment(tossRequest);
        } catch (Exception e) {
            // 토스 API 호출 실패
//            log.error("[승인] 토스 API 호출 실패", e);
            reservation.markAsFailed();
            paymentReservationRepository.save(reservation);
            throw e;
        }

        if (!tossResponse.isSuccess()) {
//            log.error("[승인] 토스 결제 실패 - code: {}, message: {}",
//                    tossResponse.getCode(), tossResponse.getMessage());
            reservation.markAsFailed();
            paymentReservationRepository.save(reservation);
            throw new CustomException(ErrorCode.PAYMENT_CONFIRM_FAILED);
        }

//        log.info("[승인] 토스 결제 성공 - paymentKey: {}", tossResponse.getPaymentKey());

        // 9. 포인트 적립 및 예약 완료 처리 (분산 트랜잭션 보상 처리)
        try {
            long paymentAmount = request.getAmount();
            PrepaymentResponseDto response = savePaymentAndPoints(
                    wallet,
                    reservation.getStore(),
                    paymentAmount,
                    tossResponse
            );

            // 10. 예약 완료 처리
            reservation.markAsCompleted(request.getPaymentKey());
            paymentReservationRepository.save(reservation);

//            log.info("[승인] 완료 - 거래ID: {}, 적립포인트: {}P",
//                    response.getTransactionId(), paymentAmount);

            return IdempotentResult.created(response);

        } catch (Exception e) {
            // 토스 결제는 성공했지만 DB 저장 실패 → 보상 트랜잭션 (자동 취소)
//            log.error("[승인] DB 저장 실패, 보상 트랜잭션 시작 - paymentKey: {}", request.getPaymentKey(), e);

            try {
                compensatePayment(request.getPaymentKey(), "시스템 오류로 인한 자동 취소");
                reservation.markAsFailed();
                paymentReservationRepository.save(reservation);
            } catch (Exception compensateEx) {
//                log.error("[승인] 보상 트랜잭션 실패 - 수동 처리 필요! paymentKey: {}",
//                        request.getPaymentKey(), compensateEx);
                // TODO: 알람 발송, 관리자 알림 등
            }

            throw new CustomException(ErrorCode.PAYMENT_CONFIRM_FAILED);
        }
    }

    /**
     * 보상 트랜잭션: 토스 결제 취소
     * 토스 결제는 성공했지만 DB 저장 실패 시 자동으로 취소
     */
    private void compensatePayment(String paymentKey, String cancelReason) {
//        log.warn("[보상] 토스 결제 취소 시작 - paymentKey: {}, 이유: {}", paymentKey, cancelReason);

        TossCancelRequest tossCancelRequest = TossCancelRequest.builder()
                .cancelReason(cancelReason)
                .build();

        TossCancelResponse cancelResponse = tossPaymentClient.cancelPayment(paymentKey, tossCancelRequest);

        if (cancelResponse.isSuccess()) {
//            log.info("[보상] 토스 결제 취소 성공 - paymentKey: {}", paymentKey);
        } else {
//            log.error("[보상] 토스 결제 취소 실패 - paymentKey: {}, code: {}, message: {}",
//                    paymentKey, cancelResponse.getCode(), cancelResponse.getMessage());
            throw new CustomException(ErrorCode.PAYMENT_CANCEL_FAILED);
        }
    }

    /**
     * 개인 지갑 조회 또는 생성
     */
    private Wallet findOrCreateIndividualWallet(Customer customer) {
        return walletRepository.findByCustomerAndWalletType(customer, WalletType.INDIVIDUAL)
                .orElseGet(() -> {
                    Wallet newWallet = Wallet.builder()
                            .customer(customer)
                            .walletType(WalletType.INDIVIDUAL)
                            .build();
                    return walletRepository.save(newWallet);
                });
    }

    /**
     * 결제 성공 후 포인트 적립
     * 보너스/정산 시스템 제거, 결제금액 = 포인트로 단순화
     */
    private PrepaymentResponseDto savePaymentAndPoints(
            Wallet wallet,
            Store store,
            long paymentAmount,
            TossPaymentConfirmResponse tossResponse) {

        LocalDateTime now = LocalDateTime.now();

        // 1. 보너스 정책 조회
        Optional<ChargeBonus> chargeBonusOpt = chargeBonusService.findChargeBonusByAmount(
                store.getStoreId(),
                paymentAmount
        );

        int bonusPercentage = 0;
        long bonusAmount = 0L;

        if (chargeBonusOpt.isPresent()) {
            bonusPercentage = chargeBonusOpt.get().getBonusPercentage();
            bonusAmount = (paymentAmount * bonusPercentage) / 100;
//            log.info("[보너스] 적용 - {}원 × {}% = {}원", paymentAmount, bonusPercentage, bonusAmount);
        }

        long totalPoints = paymentAmount + bonusAmount;

        // 2. Transaction 생성 (원금만 저장)
        Transaction transaction = Transaction.builder()
                .wallet(wallet)
                .customer(wallet.getCustomer())
                .store(store)
                .transactionType(TransactionType.CHARGE)
                .amount(paymentAmount)
                .transactionUniqueNo(tossResponse.getPaymentKey())
                .build();
        transaction = transactionRepository.save(transaction);

        // 3. WalletStoreLot 생성 (만료일: 1년 후, 원금+보너스)
        LocalDateTime expiredAt = now.plusYears(1);
        WalletStoreLot lot = WalletStoreLot.builder()
                .wallet(wallet)
                .store(store)
                .amountTotal(totalPoints)
                .amountRemaining(totalPoints)
                .acquiredAt(now)
                .expiredAt(expiredAt)
                .sourceType(LotSourceType.CHARGE)
                .lotStatus(LotStatus.ACTIVE)
                .originChargeTransaction(transaction)
                .build();
        walletStoreLotRepository.save(lot);

        // 4. WalletStoreBalance 업데이트 또는 생성
        WalletStoreBalance balance = walletStoreBalanceRepository
                .findByWalletAndStore(wallet, store)
                .orElseGet(() -> WalletStoreBalance.builder()
                        .wallet(wallet)
                        .store(store)
                        .balance(0L)
                        .build());

        balance.addBalance(totalPoints);
        walletStoreBalanceRepository.save(balance);

//        log.info("[선결제] 포인트 적립 완료 - 고객ID: {}, 결제금액: {}원, 보너스: {}원 ({}%), 총포인트: {}P",
//                wallet.getCustomer().getCustomerId(), paymentAmount, bonusAmount, bonusPercentage, totalPoints);

        // 5. 응답 생성
        return PrepaymentResponseDto.builder()
                .transactionId(transaction.getTransactionId())
                .transactionUniqueNo(tossResponse.getPaymentKey())
                .storeId(store.getStoreId())
                .storeName(store.getStoreName())
                .paymentAmount(paymentAmount)
                .bonusPercentage(bonusPercentage)
                .bonusAmount(bonusAmount)
                .totalPoints(totalPoints)
                .transactionTime(transaction.getCreatedAt())
                .remainingBalance(balance.getBalance())
                .build();
    }
}