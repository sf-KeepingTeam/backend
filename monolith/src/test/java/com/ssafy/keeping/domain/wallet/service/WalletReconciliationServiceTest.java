package com.ssafy.keeping.domain.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.wallet.dto.ReconcileReport;
import com.ssafy.keeping.domain.wallet.model.Wallet;
import com.ssafy.keeping.domain.wallet.model.WalletStoreBalance;
import com.ssafy.keeping.domain.wallet.repository.WalletStoreBalanceRepository;
import com.ssafy.keeping.global.config.WalletLedgerProperties;
import com.ssafy.keeping.global.metrics.LedgerMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class WalletReconciliationServiceTest {

  @Mock private WalletStoreBalanceRepository balanceRepository;
  @Mock private WalletLedgerService walletLedgerService;

  private WalletLedgerProperties properties;
  private LedgerMetrics ledgerMetrics;
  private WalletReconciliationService sut;

  @BeforeEach
  void setUp() {
    properties = new WalletLedgerProperties();
    properties.getReconcile().setPageSize(100);
    ledgerMetrics = new LedgerMetrics(new SimpleMeterRegistry());
    sut =
        new WalletReconciliationService(
            balanceRepository, walletLedgerService, properties, ledgerMetrics);
  }

  private WalletStoreBalance buildBalance(Long walletId, Long storeId, long balance) {
    Wallet wallet = mock(Wallet.class);
    when(wallet.getWalletId()).thenReturn(walletId);
    Store store = mock(Store.class);
    when(store.getStoreId()).thenReturn(storeId);
    return WalletStoreBalance.builder()
        .wallet(wallet)
        .store(store)
        .balance(balance)
        .build();
  }

  @Test
  @DisplayName("C-1: 정상 데이터 (balance == lotSum) → mismatchCount = 0")
  void no_mismatch_when_balance_equals_lot_sum() {
    // given
    WalletStoreBalance bal = buildBalance(1L, 10L, 5000L);

    PageImpl<WalletStoreBalance> page =
        new PageImpl<>(List.of(bal), PageRequest.of(0, 100), 1);
    when(balanceRepository.findAll(any(Pageable.class))).thenReturn(page);

    // lotSum == balance → 불일치 없음 → recheck 호출 자체가 일어나지 않음
    when(walletLedgerService.sumActiveLotRemaining(1L, 10L)).thenReturn(5000L);

    // when
    ReconcileReport report = sut.runOnce();

    // then
    assertThat(report.mismatchCount()).isEqualTo(0);
    assertThat(report.balanceOverLotSum()).isEqualTo(0);
    assertThat(report.balanceUnderLotSum()).isEqualTo(0);
    assertThat(report.scannedPairs()).isEqualTo(1);
  }

  @Test
  @DisplayName("C-2: balance를 직접 +1000 → 1건 검출, balanceOverLotSum = 1000")
  void detects_balance_over_lot_sum() {
    // given: balance = 6000, lotSum = 5000 → diff = +1000
    WalletStoreBalance bal = buildBalance(1L, 10L, 6000L);

    PageImpl<WalletStoreBalance> page =
        new PageImpl<>(List.of(bal), PageRequest.of(0, 100), 1);
    when(balanceRepository.findAll(any(Pageable.class))).thenReturn(page);

    when(walletLedgerService.sumActiveLotRemaining(1L, 10L)).thenReturn(5000L);
    // recheck도 동일 불일치 확인
    when(balanceRepository.findByWalletIdAndStoreId(1L, 10L))
        .thenReturn(Optional.of(bal));

    // when
    ReconcileReport report = sut.runOnce();

    // then
    assertThat(report.mismatchCount()).isEqualTo(1);
    assertThat(report.balanceOverLotSum()).isEqualTo(1000L);
    assertThat(report.balanceUnderLotSum()).isEqualTo(0);
  }

  @Test
  @DisplayName("C-3: balance를 직접 -1000 → 1건 검출, balanceUnderLotSum = 1000")
  void detects_balance_under_lot_sum() {
    // given: balance = 4000, lotSum = 5000 → diff = -1000
    WalletStoreBalance bal = buildBalance(1L, 10L, 4000L);

    PageImpl<WalletStoreBalance> page =
        new PageImpl<>(List.of(bal), PageRequest.of(0, 100), 1);
    when(balanceRepository.findAll(any(Pageable.class))).thenReturn(page);

    when(walletLedgerService.sumActiveLotRemaining(1L, 10L)).thenReturn(5000L);
    // recheck도 동일 불일치 확인
    when(balanceRepository.findByWalletIdAndStoreId(1L, 10L))
        .thenReturn(Optional.of(bal));

    // when
    ReconcileReport report = sut.runOnce();

    // then
    assertThat(report.mismatchCount()).isEqualTo(1);
    assertThat(report.balanceOverLotSum()).isEqualTo(0);
    assertThat(report.balanceUnderLotSum()).isEqualTo(1000L);
  }

  @Test
  @DisplayName("C-6: runOnce()를 직접 호출 → 정상 동작 (단독 실행 가능)")
  void runOnce_can_be_called_directly() {
    // given: 빈 페이지
    PageImpl<WalletStoreBalance> emptyPage =
        new PageImpl<>(List.of(), PageRequest.of(0, 100), 0);
    when(balanceRepository.findAll(any(Pageable.class))).thenReturn(emptyPage);

    // when
    ReconcileReport report = sut.runOnce();

    // then: 정상적으로 빈 리포트 반환
    assertThat(report).isNotNull();
    assertThat(report.scannedPairs()).isEqualTo(0);
    assertThat(report.mismatchCount()).isEqualTo(0);
    assertThat(report.balanceOverLotSum()).isEqualTo(0);
    assertThat(report.balanceUnderLotSum()).isEqualTo(0);
    assertThat(report.samples()).isEmpty();
    assertThat(report.startedAt()).isNotNull();
    assertThat(report.finishedAt()).isNotNull();
  }
}
