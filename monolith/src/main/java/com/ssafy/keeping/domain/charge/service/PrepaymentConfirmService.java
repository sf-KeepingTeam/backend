package com.ssafy.keeping.domain.charge.service;

import com.ssafy.keeping.domain.charge.dto.ConfirmPrepareResult;
import com.ssafy.keeping.domain.charge.dto.request.PrepaymentConfirmRequest;
import com.ssafy.keeping.domain.charge.dto.response.PrepaymentResponseDto;
import com.ssafy.keeping.domain.charge.model.ChargeBonus;
import com.ssafy.keeping.domain.charge.model.PaymentReservation;
import com.ssafy.keeping.domain.charge.repository.PaymentReservationRepository;
import com.ssafy.keeping.domain.idempotency.model.IdempotentResult;
import com.ssafy.keeping.domain.payment.toss.dto.TossPaymentConfirmResponse;
import com.ssafy.keeping.domain.payment.transactions.constant.TransactionType;
import com.ssafy.keeping.domain.payment.transactions.model.Transaction;
import com.ssafy.keeping.domain.payment.transactions.repository.TransactionRepository;
import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.wallet.constant.LotSourceType;
import com.ssafy.keeping.domain.wallet.constant.LotStatus;
import com.ssafy.keeping.domain.wallet.constant.WalletType;
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

/**
 * 충전 Phase A (검증·지갑 확보) + Phase B (적립) 전담 빈.
 * PrepaymentService(saga 오케스트레이터)에서 호출.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PrepaymentConfirmService {

    private final PaymentReservationRepository paymentReservationRepository;
    private final TransactionRepository transactionRepository;
    private final WalletRepository walletRepository;
    private final WalletStoreLotRepository walletStoreLotRepository;
    private final WalletStoreBalanceRepository walletStoreBalanceRepository;
    private final ChargeBonusService chargeBonusService;

    /**
     * Phase A: 예약 비관락 조회 + 검증 + 멱등성(COMPLETED→replay) + 지갑 확보.
     * 커밋 후 비관락 해제 — 토스 호출 전에 락을 푼다.
     */
    @Transactional
    public ConfirmPrepareResult prepareConfirm(
            Long storeId, Long customerId, PrepaymentConfirmRequest request) {

        // 1. 예약 조회 (비관적 락)
        PaymentReservation reservation = paymentReservationRepository
                .findByOrderIdWithLock(request.getOrderId())
                .orElseThrow(() -> new CustomException(ErrorCode.TRANSACTION_NOT_FOUND));

        // 2. 소유권 검증
        if (!reservation.getCustomer().getCustomerId().equals(customerId)) {
            throw new CustomException(ErrorCode.UNAUTHORIZED_ACCESS);
        }

        // 3. 가게 검증
        if (!reservation.getStore().getStoreId().equals(storeId)) {
            throw new CustomException(ErrorCode.INVALID_REQUEST);
        }

        // 4. 금액 검증
        if (!reservation.getAmount().equals(request.getAmount())) {
            throw new CustomException(ErrorCode.INVALID_REQUEST);
        }

        // 5. 만료 확인
        if (reservation.isExpired()) {
            reservation.markAsExpired();
            paymentReservationRepository.save(reservation);
            throw new CustomException(ErrorCode.INVALID_REQUEST);
        }

        // 6. 멱등성 — 이미 COMPLETED면 기존 결과 replay
        if (reservation.getStatus() == PaymentReservation.ReservationStatus.COMPLETED) {
            Transaction existingTx = transactionRepository
                    .findByTransactionUniqueNo(reservation.getPaymentKey())
                    .orElseThrow(() -> new CustomException(ErrorCode.TRANSACTION_NOT_FOUND));

            WalletStoreBalance balance = walletStoreBalanceRepository
                    .findByWalletAndStore(existingTx.getWallet(), existingTx.getStore())
                    .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));

            WalletStoreLot existingLot = walletStoreLotRepository
                    .findByOriginChargeTransaction(existingTx)
                    .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));

            long paymentAmount = existingTx.getAmount();
            long totalPoints = existingLot.getAmountTotal();
            long bonusAmount = totalPoints - paymentAmount;
            int bonusPercentage = bonusAmount > 0 ? (int) ((bonusAmount * 100) / paymentAmount) : 0;

            PrepaymentResponseDto replayDto = PrepaymentResponseDto.builder()
                    .transactionId(existingTx.getTransactionId())
                    .transactionUniqueNo(existingTx.getTransactionUniqueNo())
                    .storeId(existingTx.getStore().getStoreId())
                    .storeName(existingTx.getStore().getStoreName())
                    .paymentAmount(paymentAmount)
                    .bonusPercentage(bonusPercentage)
                    .bonusAmount(bonusAmount)
                    .totalPoints(totalPoints)
                    .transactionTime(existingTx.getCreatedAt())
                    .remainingBalance(balance.getBalance())
                    .build();

            return ConfirmPrepareResult.builder()
                    .reservationId(reservation.getReservationId())
                    .orderId(request.getOrderId())
                    .replayResponse(IdempotentResult.okReplay(replayDto))
                    .build();
        }

        // 7. 지갑 확보
        Wallet wallet = findOrCreateIndividualWallet(reservation.getCustomer());

        log.info("[충전-PhaseA] 검증 완료 - orderId: {}, amount: {}", request.getOrderId(), reservation.getAmount());

        return ConfirmPrepareResult.builder()
                .reservationId(reservation.getReservationId())
                .orderId(request.getOrderId())
                .paymentKey(request.getPaymentKey())
                .amount(reservation.getAmount())
                .store(reservation.getStore())
                .wallet(wallet)
                .build();
    }

    /**
     * Phase B: 예약 재-비관락 + COMPLETED 재확인(멱등) → 적립 + markAsCompleted.
     */
    @Transactional
    public PrepaymentResponseDto creditPoints(
            String orderId,
            String paymentKey,
            Wallet wallet,
            Store store,
            long paymentAmount,
            TossPaymentConfirmResponse tossResponse) {

        // 재-비관락으로 동시성 보호
        PaymentReservation reservation = paymentReservationRepository
                .findByOrderIdWithLock(orderId)
                .orElseThrow(() -> new CustomException(ErrorCode.TRANSACTION_NOT_FOUND));

        // COMPLETED 재확인 (Phase A↔Phase B 사이 동시 요청 방어)
        if (reservation.getStatus() == PaymentReservation.ReservationStatus.COMPLETED) {
            log.info("[충전-PhaseB] 이미 적립됨(멱등) - orderId: {}", orderId);
            Transaction existingTx = transactionRepository
                    .findByTransactionUniqueNo(reservation.getPaymentKey())
                    .orElseThrow(() -> new CustomException(ErrorCode.TRANSACTION_NOT_FOUND));
            WalletStoreBalance balance = walletStoreBalanceRepository
                    .findByWalletAndStore(existingTx.getWallet(), existingTx.getStore())
                    .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));
            WalletStoreLot lot = walletStoreLotRepository
                    .findByOriginChargeTransaction(existingTx)
                    .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));
            long total = lot.getAmountTotal();
            long bonus = total - existingTx.getAmount();
            int pct = bonus > 0 ? (int) ((bonus * 100) / existingTx.getAmount()) : 0;
            return PrepaymentResponseDto.builder()
                    .transactionId(existingTx.getTransactionId())
                    .transactionUniqueNo(existingTx.getTransactionUniqueNo())
                    .storeId(store.getStoreId())
                    .storeName(store.getStoreName())
                    .paymentAmount(existingTx.getAmount())
                    .bonusPercentage(pct)
                    .bonusAmount(bonus)
                    .totalPoints(total)
                    .transactionTime(existingTx.getCreatedAt())
                    .remainingBalance(balance.getBalance())
                    .build();
        }

        LocalDateTime now = LocalDateTime.now();

        // 보너스 계산
        Optional<ChargeBonus> chargeBonusOpt = chargeBonusService.findChargeBonusByAmount(
                store.getStoreId(), paymentAmount);
        int bonusPercentage = 0;
        long bonusAmount = 0L;
        if (chargeBonusOpt.isPresent()) {
            bonusPercentage = chargeBonusOpt.get().getBonusPercentage();
            bonusAmount = (paymentAmount * bonusPercentage) / 100;
        }
        long totalPoints = paymentAmount + bonusAmount;

        // Transaction 생성
        Transaction transaction = Transaction.builder()
                .wallet(wallet)
                .customer(wallet.getCustomer())
                .store(store)
                .transactionType(TransactionType.CHARGE)
                .amount(paymentAmount)
                .transactionUniqueNo(tossResponse.getPaymentKey())
                .build();
        transaction = transactionRepository.save(transaction);

        // WalletStoreLot 생성
        WalletStoreLot lot = WalletStoreLot.builder()
                .wallet(wallet)
                .store(store)
                .amountTotal(totalPoints)
                .amountRemaining(totalPoints)
                .acquiredAt(now)
                .expiredAt(now.plusYears(1))
                .sourceType(LotSourceType.CHARGE)
                .lotStatus(LotStatus.ACTIVE)
                .originChargeTransaction(transaction)
                .build();
        walletStoreLotRepository.save(lot);

        // WalletStoreBalance 업데이트
        WalletStoreBalance balance = walletStoreBalanceRepository
                .findByWalletAndStore(wallet, store)
                .orElseGet(() -> WalletStoreBalance.builder()
                        .wallet(wallet)
                        .store(store)
                        .balance(0L)
                        .build());
        balance.addBalance(totalPoints);
        walletStoreBalanceRepository.save(balance);

        // 예약 완료
        reservation.markAsCompleted(tossResponse.getPaymentKey());
        paymentReservationRepository.save(reservation);

        log.info("[충전-PhaseB] 적립 완료 - orderId: {}, 결제: {}원, 보너스: {}원, 총: {}P",
                orderId, paymentAmount, bonusAmount, totalPoints);

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

    private Wallet findOrCreateIndividualWallet(com.ssafy.keeping.domain.user.customer.model.Customer customer) {
        return walletRepository.findByCustomerAndWalletType(customer, WalletType.INDIVIDUAL)
                .orElseGet(() -> {
                    Wallet newWallet = Wallet.builder()
                            .customer(customer)
                            .walletType(WalletType.INDIVIDUAL)
                            .build();
                    return walletRepository.save(newWallet);
                });
    }
}
