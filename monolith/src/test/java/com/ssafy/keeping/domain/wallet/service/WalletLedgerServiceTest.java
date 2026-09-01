package com.ssafy.keeping.domain.wallet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;

import com.ssafy.keeping.domain.payment.transactions.model.Transaction;
import com.ssafy.keeping.domain.wallet.constant.LotStatus;
import com.ssafy.keeping.domain.wallet.model.WalletLotMove;
import com.ssafy.keeping.domain.wallet.model.WalletStoreBalance;
import com.ssafy.keeping.domain.wallet.model.WalletStoreLot;
import com.ssafy.keeping.domain.wallet.repository.WalletLotMoveRepository;
import com.ssafy.keeping.domain.wallet.repository.WalletStoreLotRepository;
import com.ssafy.keeping.global.metrics.LedgerMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WalletLedgerServiceTest {

  @Mock private WalletStoreLotRepository lotRepository;
  @Mock private WalletLotMoveRepository lotMoveRepository;

  private LedgerMetrics ledgerMetrics;
  private WalletLedgerService sut;

  @BeforeEach
  void setUp() {
    ledgerMetrics = new LedgerMetrics(new SimpleMeterRegistry());
    sut = new WalletLedgerService(lotRepository, lotMoveRepository, ledgerMetrics);
  }

  // ===== restoreLotsByOriginalTx 테스트 (C-4, R-4) =====

  @Nested
  @DisplayName("R-4: restoreLotsByOriginalTx")
  class RestoreLotsByOriginalTx {

    @Test
    @DisplayName("R-4: USE move(delta<0)의 lot이 복원되고 move(+) 기록이 생성된다")
    void restores_lots_and_creates_moves() {
      // given
      Transaction original = Transaction.builder().transactionId(100L).amount(3000L).build();
      Transaction cancelTx = Transaction.builder().transactionId(200L).build();

      WalletStoreLot lot1 =
          WalletStoreLot.builder()
              .lotId(1L)
              .amountTotal(5000L)
              .amountRemaining(3000L)
              .lotStatus(LotStatus.ACTIVE)
              .build();
      WalletStoreLot lot2 =
          WalletStoreLot.builder()
              .lotId(2L)
              .amountTotal(2000L)
              .amountRemaining(0L)
              .lotStatus(LotStatus.ACTIVE)
              .build();

      WalletLotMove move1 = mock(WalletLotMove.class);
      when(move1.getDelta()).thenReturn(-2000L);
      when(move1.getLot()).thenReturn(lot1);

      WalletLotMove move2 = mock(WalletLotMove.class);
      when(move2.getDelta()).thenReturn(-1000L);
      when(move2.getLot()).thenReturn(lot2);

      when(lotMoveRepository.findAllByTransactionIdWithLotLock(100L))
          .thenReturn(List.of(move1, move2));

      // when
      long result = sut.restoreLotsByOriginalTx(original, cancelTx);

      // then
      assertThat(result).isEqualTo(3000L);
      assertThat(lot1.getAmountRemaining()).isEqualTo(5000L); // 3000 + 2000
      assertThat(lot2.getAmountRemaining()).isEqualTo(1000L); // 0 + 1000

      ArgumentCaptor<WalletLotMove> captor = ArgumentCaptor.forClass(WalletLotMove.class);
      verify(lotMoveRepository, times(2)).save(captor.capture());
      List<WalletLotMove> savedMoves = captor.getAllValues();
      assertThat(savedMoves).hasSize(2);
    }

    @Test
    @DisplayName("R-6: 양수 delta move는 무시된다(방어)")
    void skips_positive_delta_moves() {
      Transaction original = Transaction.builder().transactionId(100L).build();
      Transaction cancelTx = Transaction.builder().transactionId(200L).build();

      WalletLotMove positiveMove = mock(WalletLotMove.class);
      when(positiveMove.getDelta()).thenReturn(500L);

      when(lotMoveRepository.findAllByTransactionIdWithLotLock(100L))
          .thenReturn(List.of(positiveMove));

      long result = sut.restoreLotsByOriginalTx(original, cancelTx);

      assertThat(result).isEqualTo(0L);
      verify(lotMoveRepository, never()).save(any());
    }

    @Test
    @DisplayName("C-4: remaining + restore > total이면 FUNDS_INVARIANT_VIOLATION 예외")
    void throws_when_restore_exceeds_total() {
      Transaction original = Transaction.builder().transactionId(100L).build();
      Transaction cancelTx = Transaction.builder().transactionId(200L).build();

      WalletStoreLot lot =
          WalletStoreLot.builder()
              .lotId(1L)
              .amountTotal(5000L)
              .amountRemaining(4500L) // 4500 + 1000 > 5000
              .lotStatus(LotStatus.ACTIVE)
              .build();

      WalletLotMove move = mock(WalletLotMove.class);
      when(move.getDelta()).thenReturn(-1000L);
      when(move.getLot()).thenReturn(lot);

      when(lotMoveRepository.findAllByTransactionIdWithLotLock(100L))
          .thenReturn(List.of(move));

      assertThatThrownBy(() -> sut.restoreLotsByOriginalTx(original, cancelTx))
          .isInstanceOf(CustomException.class)
          .satisfies(
              e -> assertThat(((CustomException) e).getErrorCode())
                  .isEqualTo(ErrorCode.FUNDS_INVARIANT_VIOLATION));
    }
  }

  // ===== settleExpiredLots 테스트 (E-1~E-7) =====

  @Nested
  @DisplayName("settleExpiredLots")
  class SettleExpiredLots {

    @Test
    @DisplayName("E-1: 만료된 lot이 정산되고 balance가 차감된다")
    void settles_expired_lots_and_deducts_balance() {
      Long walletId = 10L;
      Long storeId = 20L;
      LocalDateTime now = LocalDateTime.of(2026, 8, 21, 3, 10);

      WalletStoreLot lot1 =
          WalletStoreLot.builder()
              .lotId(1L)
              .amountTotal(5000L)
              .amountRemaining(3000L)
              .lotStatus(LotStatus.ACTIVE)
              .expiredAt(now.minusDays(1))
              .build();
      WalletStoreLot lot2 =
          WalletStoreLot.builder()
              .lotId(2L)
              .amountTotal(2000L)
              .amountRemaining(2000L)
              .lotStatus(LotStatus.ACTIVE)
              .expiredAt(now.minusDays(2))
              .build();

      when(lotRepository.lockUnsettledExpiredLots(walletId, storeId, now))
          .thenReturn(List.of(lot1, lot2));

      WalletStoreBalance balance =
          WalletStoreBalance.builder().balance(10000L).build();

      long result = sut.settleExpiredLots(walletId, storeId, now, balance);

      assertThat(result).isEqualTo(5000L); // 3000 + 2000
      assertThat(lot1.getAmountRemaining()).isEqualTo(0L);
      assertThat(lot2.getAmountRemaining()).isEqualTo(0L);
      assertThat(lot1.getExpiredSettledAt()).isEqualTo(now);
      assertThat(lot2.getExpiredSettledAt()).isEqualTo(now);
      assertThat(lot1.getExpiredAmount()).isEqualTo(3000L);
      assertThat(lot2.getExpiredAmount()).isEqualTo(2000L);
      assertThat(balance.getBalance()).isEqualTo(5000L); // 10000 - 5000
    }

    @Test
    @DisplayName("E-2: 이미 정산된 lot은 멱등하게 0을 반환한다")
    void idempotent_already_settled_lot() {
      Long walletId = 10L;
      Long storeId = 20L;
      LocalDateTime now = LocalDateTime.of(2026, 8, 21, 3, 10);
      LocalDateTime previousSettlement = now.minusHours(1);

      WalletStoreLot lot =
          WalletStoreLot.builder()
              .lotId(1L)
              .amountTotal(5000L)
              .amountRemaining(0L)
              .lotStatus(LotStatus.ACTIVE)
              .expiredAt(now.minusDays(1))
              .expiredSettledAt(previousSettlement) // 이미 정산됨
              .expiredAmount(5000L)
              .build();

      when(lotRepository.lockUnsettledExpiredLots(walletId, storeId, now))
          .thenReturn(List.of(lot));

      WalletStoreBalance balance =
          WalletStoreBalance.builder().balance(10000L).build();

      long result = sut.settleExpiredLots(walletId, storeId, now, balance);

      assertThat(result).isEqualTo(0L);
      assertThat(balance.getBalance()).isEqualTo(10000L); // 변경 없음
    }

    @Test
    @DisplayName("E-3: balance < expiredAmount이면 balance를 0으로 클램프하고 mismatch 기록")
    void clamps_balance_when_insufficient() {
      Long walletId = 10L;
      Long storeId = 20L;
      LocalDateTime now = LocalDateTime.of(2026, 8, 21, 3, 10);

      WalletStoreLot lot =
          WalletStoreLot.builder()
              .lotId(1L)
              .amountTotal(5000L)
              .amountRemaining(5000L)
              .lotStatus(LotStatus.ACTIVE)
              .expiredAt(now.minusDays(1))
              .build();

      when(lotRepository.lockUnsettledExpiredLots(walletId, storeId, now))
          .thenReturn(List.of(lot));

      WalletStoreBalance balance =
          WalletStoreBalance.builder().balance(2000L).build(); // 2000 < 5000

      long result = sut.settleExpiredLots(walletId, storeId, now, balance);

      assertThat(result).isEqualTo(5000L);
      assertThat(balance.getBalance()).isEqualTo(0L); // 클램프
      assertThat(lot.getAmountRemaining()).isEqualTo(0L);
      assertThat(lot.getExpiredAmount()).isEqualTo(5000L);
    }

    @Test
    @DisplayName("E-4: 만료 lot이 없으면 0을 반환하고 balance는 변하지 않는다")
    void no_expired_lots_returns_zero() {
      Long walletId = 10L;
      Long storeId = 20L;
      LocalDateTime now = LocalDateTime.of(2026, 8, 21, 3, 10);

      when(lotRepository.lockUnsettledExpiredLots(walletId, storeId, now))
          .thenReturn(Collections.emptyList());

      WalletStoreBalance balance =
          WalletStoreBalance.builder().balance(10000L).build();

      long result = sut.settleExpiredLots(walletId, storeId, now, balance);

      assertThat(result).isEqualTo(0L);
      assertThat(balance.getBalance()).isEqualTo(10000L);
    }

    @Test
    @DisplayName("E-5: 잔량 0인 lot은 정산되지만 소멸액 0")
    void zero_remaining_lot_settles_with_zero_amount() {
      Long walletId = 10L;
      Long storeId = 20L;
      LocalDateTime now = LocalDateTime.of(2026, 8, 21, 3, 10);

      WalletStoreLot lot =
          WalletStoreLot.builder()
              .lotId(1L)
              .amountTotal(5000L)
              .amountRemaining(0L)
              .lotStatus(LotStatus.ACTIVE)
              .expiredAt(now.minusDays(1))
              .build();

      when(lotRepository.lockUnsettledExpiredLots(walletId, storeId, now))
          .thenReturn(List.of(lot));

      WalletStoreBalance balance =
          WalletStoreBalance.builder().balance(10000L).build();

      long result = sut.settleExpiredLots(walletId, storeId, now, balance);

      // settleExpired는 amountRemaining(0)을 반환하므로 총 소멸액 0
      assertThat(result).isEqualTo(0L);
      assertThat(lot.getExpiredSettledAt()).isEqualTo(now);
      assertThat(lot.getExpiredAmount()).isEqualTo(0L);
      assertThat(balance.getBalance()).isEqualTo(10000L); // 차감 없음
    }

    @Test
    @DisplayName("E-6: settleExpired는 멱등 — 같은 lot을 두 번 호출해도 두 번째는 0")
    void settleExpired_idempotent() {
      LocalDateTime now = LocalDateTime.of(2026, 8, 21, 3, 10);

      WalletStoreLot lot =
          WalletStoreLot.builder()
              .lotId(1L)
              .amountTotal(5000L)
              .amountRemaining(3000L)
              .lotStatus(LotStatus.ACTIVE)
              .expiredAt(now.minusDays(1))
              .build();

      long first = lot.settleExpired(now);
      long second = lot.settleExpired(now);

      assertThat(first).isEqualTo(3000L);
      assertThat(second).isEqualTo(0L); // 멱등
      assertThat(lot.getAmountRemaining()).isEqualTo(0L);
      assertThat(lot.getExpiredAmount()).isEqualTo(3000L);
    }

    @Test
    @DisplayName("E-7: 복수 lot 혼합(정산됨+미정산) — 미정산분만 처리")
    void mixed_settled_and_unsettled_lots() {
      Long walletId = 10L;
      Long storeId = 20L;
      LocalDateTime now = LocalDateTime.of(2026, 8, 21, 3, 10);

      WalletStoreLot alreadySettled =
          WalletStoreLot.builder()
              .lotId(1L)
              .amountTotal(5000L)
              .amountRemaining(0L)
              .lotStatus(LotStatus.ACTIVE)
              .expiredAt(now.minusDays(2))
              .expiredSettledAt(now.minusDays(1))
              .expiredAmount(5000L)
              .build();

      WalletStoreLot unsettled =
          WalletStoreLot.builder()
              .lotId(2L)
              .amountTotal(3000L)
              .amountRemaining(1500L)
              .lotStatus(LotStatus.ACTIVE)
              .expiredAt(now.minusDays(1))
              .build();

      when(lotRepository.lockUnsettledExpiredLots(walletId, storeId, now))
          .thenReturn(List.of(alreadySettled, unsettled));

      WalletStoreBalance balance =
          WalletStoreBalance.builder().balance(10000L).build();

      long result = sut.settleExpiredLots(walletId, storeId, now, balance);

      assertThat(result).isEqualTo(1500L); // 미정산분만
      assertThat(balance.getBalance()).isEqualTo(8500L);
    }
  }

  // ===== sumActiveLotRemaining (I-1) =====

  @Nested
  @DisplayName("sumActiveLotRemaining")
  class SumActiveLotRemaining {

    @Test
    @DisplayName("I-1: 리포지토리에 위임한다")
    void delegates_to_repository() {
      when(lotRepository.sumActiveLotRemaining(10L, 20L)).thenReturn(7500L);

      long result = sut.sumActiveLotRemaining(10L, 20L);

      assertThat(result).isEqualTo(7500L);
      verify(lotRepository).sumActiveLotRemaining(10L, 20L);
    }

    @Test
    @DisplayName("I-2: lot이 없으면 0을 반환한다")
    void returns_zero_when_no_lots() {
      when(lotRepository.sumActiveLotRemaining(10L, 20L)).thenReturn(0L);

      long result = sut.sumActiveLotRemaining(10L, 20L);

      assertThat(result).isEqualTo(0L);
    }
  }
}
