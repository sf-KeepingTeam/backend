package com.ssafy.keeping.domain.wallet.service;

import com.ssafy.keeping.domain.wallet.dto.MismatchRow;
import com.ssafy.keeping.domain.wallet.dto.ReconcileReport;
import com.ssafy.keeping.domain.wallet.model.WalletStoreBalance;
import com.ssafy.keeping.domain.wallet.repository.WalletStoreBalanceRepository;
import com.ssafy.keeping.global.config.WalletLedgerProperties;
import com.ssafy.keeping.global.metrics.LedgerMetrics;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지갑 대사(Reconciliation) 서비스.
 *
 * <p>balance 행을 페이지 단위로 훑으며 ACTIVE lot 잔량 합계와 대조한다.
 * 락을 잡지 않으며, 위양성 완화를 위해 불일치 조합을 짧은 지연 후 1회 재조회한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WalletReconciliationService {

  private static final int MAX_SAMPLES = 100;
  private static final long RECHECK_DELAY_MS = 200L;

  private final WalletStoreBalanceRepository balanceRepository;
  private final WalletLedgerService walletLedgerService;
  private final WalletLedgerProperties properties;
  private final LedgerMetrics ledgerMetrics;

  /**
   * 대사 1회 실행.
   *
   * @return 대사 리포트
   */
  @Transactional(readOnly = true)
  public ReconcileReport runOnce() {
    LocalDateTime startedAt = LocalDateTime.now();
    int pageSize = properties.getReconcile().getPageSize();

    long scannedPairs = 0;
    List<MismatchRow> rawMismatches = new ArrayList<>();

    // 페이지 단위로 balance 행을 훑는다
    int pageNum = 0;
    while (true) {
      Page<WalletStoreBalance> page =
          balanceRepository.findAll(PageRequest.of(pageNum, pageSize));

      for (WalletStoreBalance bal : page.getContent()) {
        scannedPairs++;
        Long walletId = bal.getWallet().getWalletId();
        Long storeId = bal.getStore().getStoreId();
        long balance = bal.getBalance();
        long lotSum = walletLedgerService.sumActiveLotRemaining(walletId, storeId);
        long diff = balance - lotSum;

        if (diff != 0) {
          rawMismatches.add(new MismatchRow(walletId, storeId, balance, lotSum, diff));
        }
      }

      if (!page.hasNext()) break;
      pageNum++;
    }

    // 위양성 완화: 짧은 지연 후 1회 재조회
    List<MismatchRow> confirmedMismatches = recheckMismatches(rawMismatches);

    // 통계 집계
    long mismatchCount = confirmedMismatches.size();
    long balanceOverLotSum = 0;
    long balanceUnderLotSum = 0;

    for (MismatchRow row : confirmedMismatches) {
      if (row.diff() > 0) {
        balanceOverLotSum += row.diff();
      } else {
        balanceUnderLotSum += (-row.diff());
      }
    }

    // samples: 상위 100건만
    List<MismatchRow> samples =
        confirmedMismatches.size() <= MAX_SAMPLES
            ? confirmedMismatches
            : confirmedMismatches.subList(0, MAX_SAMPLES);

    LocalDateTime finishedAt = LocalDateTime.now();

    // 메트릭 + 로그
    ledgerMetrics.reconcileResult(mismatchCount, Instant.now());
    if (mismatchCount > 0) {
      for (MismatchRow row : samples) {
        ledgerMetrics.mismatch(LedgerMetrics.CAUSE_RECONCILE);
      }
    }

    log.info(
        "[RECONCILE] scannedPairs={} mismatchCount={} balanceOverLotSum={} balanceUnderLotSum={}",
        scannedPairs,
        mismatchCount,
        balanceOverLotSum,
        balanceUnderLotSum);

    return new ReconcileReport(
        startedAt,
        finishedAt,
        scannedPairs,
        mismatchCount,
        balanceOverLotSum,
        balanceUnderLotSum,
        samples);
  }

  /**
   * 불일치 조합을 짧은 지연 후 재조회하여 위양성을 걸러낸다.
   */
  private List<MismatchRow> recheckMismatches(List<MismatchRow> rawMismatches) {
    if (rawMismatches.isEmpty()) {
      return rawMismatches;
    }

    try {
      Thread.sleep(RECHECK_DELAY_MS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      // 인터럽트 시 원본 그대로 반환
      return rawMismatches;
    }

    List<MismatchRow> confirmed = new ArrayList<>();
    for (MismatchRow row : rawMismatches) {
      long lotSum = walletLedgerService.sumActiveLotRemaining(row.walletId(), row.storeId());
      // balance를 다시 조회
      long balance =
          balanceRepository
              .findByWalletIdAndStoreId(row.walletId(), row.storeId())
              .map(WalletStoreBalance::getBalance)
              .orElse(0L);
      long diff = balance - lotSum;
      if (diff != 0) {
        confirmed.add(new MismatchRow(row.walletId(), row.storeId(), balance, lotSum, diff));
      }
    }
    return confirmed;
  }
}
