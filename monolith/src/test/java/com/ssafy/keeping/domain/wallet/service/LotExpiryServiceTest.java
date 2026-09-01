package com.ssafy.keeping.domain.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.ssafy.keeping.domain.wallet.dto.ExpirySweepReport;
import com.ssafy.keeping.domain.wallet.model.WalletStoreBalance;
import com.ssafy.keeping.domain.wallet.repository.WalletStoreBalanceRepository;
import com.ssafy.keeping.domain.wallet.repository.WalletStoreLotRepository;
import com.ssafy.keeping.global.config.WalletLedgerProperties;
import com.ssafy.keeping.global.metrics.LedgerMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionCallback;

@ExtendWith(MockitoExtension.class)
class LotExpiryServiceTest {

  @Mock private WalletStoreLotRepository lotRepository;
  @Mock private WalletStoreBalanceRepository balanceRepository;
  @Mock private WalletLedgerService walletLedgerService;
  @Mock private PlatformTransactionManager txManager;

  private WalletLedgerProperties properties;
  private LedgerMetrics ledgerMetrics;
  private LotExpiryService sut;

  @BeforeEach
  void setUp() {
    properties = new WalletLedgerProperties();
    ledgerMetrics = new LedgerMetrics(new SimpleMeterRegistry());
    sut =
        new LotExpiryService(
            lotRepository,
            balanceRepository,
            walletLedgerService,
            properties,
            ledgerMetrics,
            txManager);
  }

  /**
   * TransactionTemplate.execute()를 모킹하여 콜백을 직접 실행하도록 한다.
   * PlatformTransactionManager를 mock하면 TransactionTemplate이 내부에서
   * getTransaction/commit/rollback을 호출하므로 이를 적절히 stub한다.
   */
  private void stubTxManagerToExecuteCallback() {
    when(txManager.getTransaction(any()))
        .thenReturn(mock(org.springframework.transaction.TransactionStatus.class));
    // commit은 void — 아무것도 안 하면 됨
  }

  @Test
  @DisplayName("E-5: maxGroupsPerRun 개수만큼 후보가 있을 때 처리되고, settleExpiredLots가 0을 반환한 쌍은 remainingBacklog에 포함")
  void all_candidates_processed_and_backlog_reported() {
    // given: maxGroupsPerRun = 3
    properties.getExpiry().setMaxGroupsPerRun(3);
    LocalDateTime now = LocalDateTime.of(2026, 8, 21, 3, 10);

    // 3개의 후보 쌍
    List<Object[]> candidates =
        List.of(
            new Object[] {1L, 10L},
            new Object[] {2L, 20L},
            new Object[] {3L, 30L});

    when(lotRepository.findExpiryCandidatePairs(now, 3)).thenReturn(candidates);
    stubTxManagerToExecuteCallback();

    // 각 쌍에 대해 balance 락 + settleExpiredLots
    WalletStoreBalance bal1 = WalletStoreBalance.builder().balance(5000L).build();
    WalletStoreBalance bal2 = WalletStoreBalance.builder().balance(3000L).build();
    WalletStoreBalance bal3 = WalletStoreBalance.builder().balance(7000L).build();

    when(balanceRepository.lockByWalletIdAndStoreId(1L, 10L)).thenReturn(Optional.of(bal1));
    when(balanceRepository.lockByWalletIdAndStoreId(2L, 20L)).thenReturn(Optional.of(bal2));
    when(balanceRepository.lockByWalletIdAndStoreId(3L, 30L)).thenReturn(Optional.of(bal3));

    // 1번, 3번은 정상 정산. 2번은 만료 lot이 없어서 0 반환 → settled 아님 → backlog에 남음
    when(walletLedgerService.settleExpiredLots(eq(1L), eq(10L), eq(now), any())).thenReturn(2000L);
    when(walletLedgerService.settleExpiredLots(eq(2L), eq(20L), eq(now), any())).thenReturn(0L);
    when(walletLedgerService.settleExpiredLots(eq(3L), eq(30L), eq(now), any())).thenReturn(500L);

    // when
    ExpirySweepReport report = sut.sweepOnce(now);

    // then: 2 groups settled, 1 not settled → remainingBacklog = 3 - 2 - 0 = 1
    assertThat(report.settledGroups()).isEqualTo(2);
    assertThat(report.settledAmount()).isEqualTo(2500L); // 2000 + 500
    assertThat(report.skippedLocked()).isEqualTo(0);
    assertThat(report.remainingBacklog()).isEqualTo(1);
  }

  @Test
  @DisplayName("후보 0건이면 빈 리포트 반환")
  void empty_candidates_returns_empty_report() {
    // given
    LocalDateTime now = LocalDateTime.of(2026, 8, 21, 3, 10);
    when(lotRepository.findExpiryCandidatePairs(now, properties.getExpiry().getMaxGroupsPerRun()))
        .thenReturn(Collections.emptyList());

    // when
    ExpirySweepReport report = sut.sweepOnce(now);

    // then
    assertThat(report.settledGroups()).isEqualTo(0);
    assertThat(report.settledLots()).isEqualTo(0);
    assertThat(report.settledAmount()).isEqualTo(0);
    assertThat(report.skippedLocked()).isEqualTo(0);
    assertThat(report.remainingBacklog()).isEqualTo(0);

    // balance 락 시도조차 하지 않음
    verifyNoInteractions(balanceRepository);
    verifyNoInteractions(walletLedgerService);
  }

  @Test
  @DisplayName("balance 락 획득 실패(PessimisticLockingFailureException) → skippedLocked++ 증가, 예외 없이 다음으로 진행")
  void pessimistic_lock_failure_increments_skipped_and_continues() {
    // given
    properties.getExpiry().setMaxGroupsPerRun(3);
    LocalDateTime now = LocalDateTime.of(2026, 8, 21, 3, 10);

    List<Object[]> candidates =
        List.of(
            new Object[] {1L, 10L},
            new Object[] {2L, 20L});

    when(lotRepository.findExpiryCandidatePairs(now, 3)).thenReturn(candidates);
    stubTxManagerToExecuteCallback();

    // 첫 번째 쌍: 락 실패
    when(balanceRepository.lockByWalletIdAndStoreId(1L, 10L))
        .thenThrow(new PessimisticLockingFailureException("lock timeout"));

    // 두 번째 쌍: 정상 처리
    WalletStoreBalance bal2 = WalletStoreBalance.builder().balance(5000L).build();
    when(balanceRepository.lockByWalletIdAndStoreId(2L, 20L)).thenReturn(Optional.of(bal2));
    when(walletLedgerService.settleExpiredLots(eq(2L), eq(20L), eq(now), any())).thenReturn(1500L);

    // when
    ExpirySweepReport report = sut.sweepOnce(now);

    // then
    assertThat(report.skippedLocked()).isEqualTo(1);
    assertThat(report.settledGroups()).isEqualTo(1);
    assertThat(report.settledAmount()).isEqualTo(1500L);
  }

  @Test
  @DisplayName("balance 행이 null이면 건너뜀 — 더미 balance(0)로 처리됨")
  void null_balance_row_proceeds_with_dummy_balance() {
    // given
    LocalDateTime now = LocalDateTime.of(2026, 8, 21, 3, 10);

    List<Object[]> candidates = Collections.singletonList(new Object[] {1L, 10L});
    when(lotRepository.findExpiryCandidatePairs(now, properties.getExpiry().getMaxGroupsPerRun()))
        .thenReturn(candidates);
    stubTxManagerToExecuteCallback();

    // balance 락은 빈 Optional 반환
    when(balanceRepository.lockByWalletIdAndStoreId(1L, 10L)).thenReturn(Optional.empty());

    // settleExpiredLots는 더미 balance(0)로 호출됨 — expired amount 반환
    when(walletLedgerService.settleExpiredLots(eq(1L), eq(10L), eq(now), any())).thenReturn(3000L);

    // when
    ExpirySweepReport report = sut.sweepOnce(now);

    // then: 정상 처리됨 (예외 없음)
    assertThat(report.settledGroups()).isEqualTo(1);
    assertThat(report.settledAmount()).isEqualTo(3000L);
    assertThat(report.skippedLocked()).isEqualTo(0);
  }

  @Test
  @DisplayName("정상 처리: settledGroups, settledAmount 집계 정확")
  void normal_processing_aggregates_correctly() {
    // given
    properties.getExpiry().setMaxGroupsPerRun(5);
    LocalDateTime now = LocalDateTime.of(2026, 8, 21, 3, 10);

    List<Object[]> candidates =
        List.of(
            new Object[] {10L, 100L},
            new Object[] {20L, 200L},
            new Object[] {30L, 300L});

    when(lotRepository.findExpiryCandidatePairs(now, 5)).thenReturn(candidates);
    stubTxManagerToExecuteCallback();

    WalletStoreBalance bal1 = WalletStoreBalance.builder().balance(10000L).build();
    WalletStoreBalance bal2 = WalletStoreBalance.builder().balance(8000L).build();
    WalletStoreBalance bal3 = WalletStoreBalance.builder().balance(6000L).build();

    when(balanceRepository.lockByWalletIdAndStoreId(10L, 100L)).thenReturn(Optional.of(bal1));
    when(balanceRepository.lockByWalletIdAndStoreId(20L, 200L)).thenReturn(Optional.of(bal2));
    when(balanceRepository.lockByWalletIdAndStoreId(30L, 300L)).thenReturn(Optional.of(bal3));

    when(walletLedgerService.settleExpiredLots(eq(10L), eq(100L), eq(now), any()))
        .thenReturn(4000L);
    when(walletLedgerService.settleExpiredLots(eq(20L), eq(200L), eq(now), any()))
        .thenReturn(2500L);
    when(walletLedgerService.settleExpiredLots(eq(30L), eq(300L), eq(now), any()))
        .thenReturn(1500L);

    // when
    ExpirySweepReport report = sut.sweepOnce(now);

    // then
    assertThat(report.settledGroups()).isEqualTo(3);
    assertThat(report.settledLots()).isEqualTo(3); // 1 lot per group minimum
    assertThat(report.settledAmount()).isEqualTo(8000L); // 4000 + 2500 + 1500
    assertThat(report.skippedLocked()).isEqualTo(0);
    assertThat(report.remainingBacklog()).isEqualTo(0); // 3 - 3 - 0 = 0
  }
}
