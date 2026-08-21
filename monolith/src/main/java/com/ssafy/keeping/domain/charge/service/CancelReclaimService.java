package com.ssafy.keeping.domain.charge.service;

import com.ssafy.keeping.domain.charge.constant.RefundStatus;
import com.ssafy.keeping.domain.charge.dto.CancelReclaimResult;
import com.ssafy.keeping.domain.charge.dto.request.CancelRequestDto;
import com.ssafy.keeping.domain.payment.transactions.constant.TransactionType;
import com.ssafy.keeping.domain.payment.transactions.model.Transaction;
import com.ssafy.keeping.domain.payment.transactions.repository.TransactionRepository;
import com.ssafy.keeping.domain.wallet.model.WalletStoreBalance;
import com.ssafy.keeping.domain.wallet.model.WalletStoreLot;
import com.ssafy.keeping.domain.wallet.repository.WalletStoreBalanceRepository;
import com.ssafy.keeping.domain.wallet.repository.WalletStoreLotRepository;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 취소 Phase A — 로컬 포인트 회수 (단일 트랜잭션, 외부 호출 없음).
 *
 * <p>CancelService(saga 오케스트레이터)에서 호출하며, 별도 빈이므로 @Transactional 프록시가 정상 동작한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CancelReclaimService {

  private final TransactionRepository transactionRepository;
  private final WalletStoreLotRepository walletStoreLotRepository;
  private final WalletStoreBalanceRepository walletStoreBalanceRepository;

  /**
   * Phase A: 포인트 회수 + cancel Transaction 저장 (refundStatus = REFUND_PENDING). 외부 호출 없음 — 이 메서드가 실패해도
   * 돈 손실 0.
   */
  @Transactional
  public CancelReclaimResult reclaimPoints(Long customerId, CancelRequestDto requestDto) {
    String paymentKey = resolvePaymentKey(requestDto);

    log.info("[취소-PhaseA] 시작 - 고객ID: {}, paymentKey: {}", customerId, paymentKey);

    // 1. 거래 조회 및 검증 (lot 비관락 포함)
    ValidationResult validated = validateCancellation(customerId, paymentKey);
    Transaction originalTransaction = validated.transaction();
    WalletStoreLot lot = validated.lot();

    // 2. balance 비관락 조회
    WalletStoreBalance balance =
        walletStoreBalanceRepository
            .findByWalletAndStoreForUpdate(
                originalTransaction.getWallet(), originalTransaction.getStore())
            .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));

    // 4. balance 차감 — 잔액 부족 시 여기서 거절 (토스 호출 전이라 손실 0)
    Long reclaimAmount = lot.getAmountTotal();
    if (balance.getBalance() < reclaimAmount) {
      log.warn("[취소-PhaseA] 잔액 부족 - balance: {}, 필요: {}", balance.getBalance(), reclaimAmount);
      throw new CustomException(ErrorCode.FUNDS_INSUFFICIENT);
    }
    balance.subtractBalance(reclaimAmount);

    // 5. cancel Transaction 생성 (refundStatus = REFUND_PENDING)
    LocalDateTime now = LocalDateTime.now();
    Transaction cancelTransaction =
        Transaction.builder()
            .wallet(originalTransaction.getWallet())
            .customer(originalTransaction.getCustomer())
            .store(originalTransaction.getStore())
            .transactionType(TransactionType.CANCEL_CHARGE)
            .amount(originalTransaction.getAmount())
            .transactionUniqueNo(originalTransaction.getTransactionUniqueNo())
            .refTransaction(originalTransaction)
            .refundStatus(RefundStatus.REFUND_PENDING)
            .build();
    cancelTransaction = transactionRepository.save(cancelTransaction);

    // 6. lot 취소 처리
    lot.setAmountRemaining(0L);
    lot.setCancelTransaction(cancelTransaction);
    lot.setCanceledAt(now);
    lot.markAsCanceled();

    log.info(
        "[취소-PhaseA] 완료 - 고객ID: {}, 원금: {}원, 총회수: {}P, refundStatus: REFUND_PENDING",
        customerId,
        originalTransaction.getAmount(),
        reclaimAmount);

    return CancelReclaimResult.builder()
        .cancelTransactionId(cancelTransaction.getTransactionId())
        .transactionUniqueNo(originalTransaction.getTransactionUniqueNo())
        .cancelAmount(originalTransaction.getAmount())
        .cancelTime(now)
        .remainingBalance(balance.getBalance())
        .paymentKey(paymentKey)
        .build();
  }

  /** refund 상태 업데이트 (Phase B 결과 반영용). */
  @Transactional
  public void updateRefundStatus(Long transactionId, RefundStatus status) {
    Transaction tx =
        transactionRepository
            .findById(transactionId)
            .orElseThrow(() -> new CustomException(ErrorCode.TRANSACTION_NOT_FOUND));
    tx.setRefundStatus(status);
    log.info("[취소-상태] txId: {} → {}", transactionId, status);
  }

  private String resolvePaymentKey(CancelRequestDto requestDto) {
    if (requestDto.getPaymentKey() != null && !requestDto.getPaymentKey().isBlank()) {
      return requestDto.getPaymentKey();
    }
    if (requestDto.getTransactionUniqueNo() != null
        && !requestDto.getTransactionUniqueNo().isBlank()) {
      return requestDto.getTransactionUniqueNo();
    }
    throw new CustomException(ErrorCode.INVALID_REQUEST);
  }

  private ValidationResult validateCancellation(Long customerId, String paymentKey) {
    Transaction originalTransaction =
        transactionRepository
            .findByTransactionUniqueNo(paymentKey)
            .orElseThrow(() -> new CustomException(ErrorCode.TRANSACTION_NOT_FOUND));

    if (!originalTransaction.getCustomer().getCustomerId().equals(customerId)) {
      throw new CustomException(ErrorCode.UNAUTHORIZED_ACCESS);
    }

    if (originalTransaction.getRefTransaction() != null) {
      throw new CustomException(ErrorCode.CANCEL_NOT_AVAILABLE);
    }

    WalletStoreLot lot =
        walletStoreLotRepository
            .findByOriginChargeTransactionWithLock(originalTransaction)
            .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));

    if (!lot.getAmountRemaining().equals(lot.getAmountTotal())) {
      log.warn(
          "[취소-PhaseA] 불가 - 포인트 일부 사용됨. 총: {}, 잔여: {}",
          lot.getAmountTotal(),
          lot.getAmountRemaining());
      throw new CustomException(ErrorCode.CANCEL_NOT_AVAILABLE);
    }

    log.info(
        "[취소-PhaseA] 검증 완료 (락 획득) - 거래ID: {}, 금액: {}",
        originalTransaction.getTransactionId(),
        originalTransaction.getAmount());

    return new ValidationResult(originalTransaction, lot);
  }

  private record ValidationResult(Transaction transaction, WalletStoreLot lot) {}
}
