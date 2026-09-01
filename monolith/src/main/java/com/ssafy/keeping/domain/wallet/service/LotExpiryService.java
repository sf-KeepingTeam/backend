package com.ssafy.keeping.domain.wallet.service;

import com.ssafy.keeping.domain.wallet.dto.ExpirySweepReport;
import com.ssafy.keeping.domain.wallet.model.WalletStoreBalance;
import com.ssafy.keeping.domain.wallet.repository.WalletStoreBalanceRepository;
import com.ssafy.keeping.domain.wallet.repository.WalletStoreLotRepository;
import com.ssafy.keeping.global.config.WalletLedgerProperties;
import com.ssafy.keeping.global.metrics.LedgerMetrics;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 만료 Lot 일괄 정산 배치 본체.
 *
 * <p>2단 구조:
 * <ol>
 *   <li>1단계(읽기전용): 락 없이 후보 (walletId, storeId) 쌍을 조회</li>
 *   <li>2단계: 각 쌍마다 독립 트랜잭션으로 balance 락 → settleExpiredLots → commit</li>
 * </ol>
 *
 * <p>self-invocation 함정 회피를 위해 2단계는 {@link TransactionTemplate}을 사용한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LotExpiryService {

  private final WalletStoreLotRepository lotRepository;
  private final WalletStoreBalanceRepository balanceRepository;
  private final WalletLedgerService walletLedgerService;
  private final WalletLedgerProperties properties;
  private final LedgerMetrics ledgerMetrics;
  private final PlatformTransactionManager txManager;

  /**
   * 만료 Lot 1회 스윕.
   *
   * @param now 기준 시각
   * @return 스윕 결과 리포트
   */
  public ExpirySweepReport sweepOnce(LocalDateTime now) {
    // ---- Phase 1: 읽기전용, 락 없이 후보 쌍 조회 ----
    int maxGroups = properties.getExpiry().getMaxGroupsPerRun();
    List<Object[]> candidates = lotRepository.findExpiryCandidatePairs(now, maxGroups);

    if (candidates.isEmpty()) {
      ledgerMetrics.expiryBacklog(0);
      return new ExpirySweepReport(0, 0, 0, 0, 0);
    }

    long settledGroups = 0;
    long settledLots = 0;
    long settledAmount = 0;
    long skippedLocked = 0;

    TransactionTemplate txTemplate = new TransactionTemplate(txManager);
    txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

    // ---- Phase 2: 각 쌍마다 독립 트랜잭션 ----
    for (Object[] pair : candidates) {
      Long walletId = ((Number) pair[0]).longValue();
      Long storeId = ((Number) pair[1]).longValue();

      try {
        long[] result =
            txTemplate.execute(
                status -> {
                  // C-2 락 순서: balance 먼저, 그 다음 lot
                  WalletStoreBalance lockedBalance =
                      balanceRepository
                          .lockByWalletIdAndStoreId(walletId, storeId)
                          .orElse(null);

                  if (lockedBalance == null) {
                    // balance 행이 없으면 lot만 정산(balance 차감 없이)
                    // 이 경우 빈 balance를 만들지 않음 — 이미 소멸된 상태
                    log.warn(
                        "[EXPIRY] balance row not found walletId={} storeId={}, settling lots without balance deduction",
                        walletId,
                        storeId);
                    lockedBalance =
                        WalletStoreBalance.builder()
                            .wallet(null)
                            .store(null)
                            .balance(0L)
                            .build();
                  }

                  long expired =
                      walletLedgerService.settleExpiredLots(walletId, storeId, now, lockedBalance);
                  // lot 개수는 expired > 0이면 최소 1그룹
                  return new long[] {expired > 0 ? 1 : 0, expired};
                });

        if (result != null && result[0] > 0) {
          settledGroups += result[0];
          settledAmount += result[1];
          // 정확한 lot 수는 settleExpiredLots 내부에서 처리하므로 여기서는 그룹 수만 추적
          settledLots += result[0]; // 최소 1 lot per group
        }
      } catch (org.springframework.dao.PessimisticLockingFailureException e) {
        skippedLocked++;
        log.debug(
            "[EXPIRY] skipped locked walletId={} storeId={}", walletId, storeId);
      } catch (jakarta.persistence.PessimisticLockException e) {
        skippedLocked++;
        log.debug(
            "[EXPIRY] skipped locked walletId={} storeId={}", walletId, storeId);
      }
    }

    // 남은 백로그 추정: 전체 후보 - 처리된 그룹 - 스킵된 그룹
    long remainingBacklog = candidates.size() - settledGroups - skippedLocked;
    if (remainingBacklog < 0) remainingBacklog = 0;

    // 메트릭 기록
    if (settledLots > 0) {
      ledgerMetrics.lotExpired(settledLots, settledAmount);
    }
    if (skippedLocked > 0) {
      ledgerMetrics.expirySkippedLocked(skippedLocked);
    }
    ledgerMetrics.expiryBacklog(remainingBacklog);

    log.info(
        "[EXPIRY] sweep complete: settledGroups={} settledAmount={} skippedLocked={} remainingBacklog={}",
        settledGroups,
        settledAmount,
        skippedLocked,
        remainingBacklog);

    return new ExpirySweepReport(
        settledGroups, settledLots, settledAmount, skippedLocked, remainingBacklog);
  }
}
