package com.ssafy.keeping.domain.wallet.service;

import com.ssafy.keeping.domain.payment.transactions.model.Transaction;
import com.ssafy.keeping.domain.wallet.model.WalletLotMove;
import com.ssafy.keeping.domain.wallet.model.WalletStoreBalance;
import com.ssafy.keeping.domain.wallet.model.WalletStoreLot;
import com.ssafy.keeping.domain.wallet.repository.WalletLotMoveRepository;
import com.ssafy.keeping.domain.wallet.repository.WalletStoreLotRepository;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import com.ssafy.keeping.global.metrics.LedgerMetrics;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 지갑 원장 핵심 로직: Lot 복원, 만료 정산, 대사용 합계 조회.
 *
 * <p>시그니처 변경 금지 — 3명의 에이전트가 이 인터페이스를 전제로 코드를 작성한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WalletLedgerService {

  private final WalletStoreLotRepository lotRepository;
  private final WalletLotMoveRepository lotMoveRepository;
  private final LedgerMetrics ledgerMetrics;

  /**
   * 결제 취소 시 원거래(USE)의 Lot을 복원한다.
   *
   * <p>PaymentRefundService 199~230행의 LOT 복원 로직과 동일하되, 합계 검증은 호출자가 수행한다.
   *
   * @param original 원 USE 거래
   * @param cancelTx 취소(CANCEL_USE) 거래
   * @return 복원된 총 금액
   */
  public long restoreLotsByOriginalTx(Transaction original, Transaction cancelTx) {
    List<WalletLotMove> usedMoves =
        lotMoveRepository.findAllByTransactionIdWithLotLock(original.getTransactionId());
    long sumRestore = 0L;

    for (WalletLotMove m : usedMoves) {
      long delta = m.getDelta(); // USE는 음수
      if (delta >= 0) continue; // 방어
      long restore = -delta; // 복원량(+)

      WalletStoreLot lot = m.getLot();
      // 상한 보호: (remaining + restore) <= total — C-4 계약: 초과 시 예외
      long newRemaining = lot.getAmountRemaining() + restore;
      if (newRemaining > lot.getAmountTotal()) {
        throw new CustomException(ErrorCode.FUNDS_INVARIANT_VIOLATION);
      }
      lot.setAmountRemaining(newRemaining);

      // 복원 move(+)
      WalletLotMove restoreMove = WalletLotMove.of(cancelTx, lot, restore);
      lotMoveRepository.save(restoreMove);

      sumRestore += restore;
    }
    return sumRestore;
  }

  /**
   * 만료 정산: 특정 (walletId, storeId)의 미정산 만료 Lot을 정산하고 balance를 차감한다.
   *
   * <p>balance 차감은 클램프 방식 — balance가 소멸액보다 작으면 balance를 0으로 내리고
   * [LEDGER_MISMATCH] 로그 + 메트릭을 기록한다.
   *
   * @param walletId 지갑 ID
   * @param storeId 매장 ID
   * @param now 현재 시각
   * @param lockedBalance 이미 PESSIMISTIC_WRITE로 잠긴 balance 행
   * @return 정산된 총 소멸액
   */
  public long settleExpiredLots(
      Long walletId, Long storeId, LocalDateTime now, WalletStoreBalance lockedBalance) {

    List<WalletStoreLot> expiredLots =
        lotRepository.lockUnsettledExpiredLots(walletId, storeId, now);

    long totalExpiredAmount = 0L;
    for (WalletStoreLot lot : expiredLots) {
      totalExpiredAmount += lot.settleExpired(now);
    }

    if (totalExpiredAmount == 0L) {
      return 0L;
    }

    // balance 차감 (클램프)
    long currentBalance = lockedBalance.getBalance();
    if (currentBalance < totalExpiredAmount) {
      log.warn(
          "[LEDGER_MISMATCH] cause=expiry_clamp walletId={} storeId={} balance={} expiredAmount={}",
          walletId,
          storeId,
          currentBalance,
          totalExpiredAmount);
      ledgerMetrics.mismatch(LedgerMetrics.CAUSE_EXPIRY_CLAMP);
      lockedBalance.setBalance(0L);
    } else {
      lockedBalance.subtractBalance(totalExpiredAmount);
    }

    return totalExpiredAmount;
  }

  /**
   * 대사용: ACTIVE Lot의 amountRemaining 합계를 조회한다.
   *
   * @param walletId 지갑 ID
   * @param storeId 매장 ID
   * @return 합계
   */
  public long sumActiveLotRemaining(Long walletId, Long storeId) {
    return lotRepository.sumActiveLotRemaining(walletId, storeId);
  }
}
