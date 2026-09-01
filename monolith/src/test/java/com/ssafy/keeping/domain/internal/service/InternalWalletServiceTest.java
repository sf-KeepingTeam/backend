package com.ssafy.keeping.domain.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.domain.idempotency.service.IdempotencyService;
import com.ssafy.keeping.domain.internal.dto.FundsCaptureRequest;
import com.ssafy.keeping.domain.internal.dto.FundsResponse;
import com.ssafy.keeping.domain.internal.dto.RefundRequest;
import com.ssafy.keeping.domain.internal.dto.RefundResponse;
import com.ssafy.keeping.domain.menu.repository.MenuRepository;
import com.ssafy.keeping.domain.payment.transactions.model.Transaction;
import com.ssafy.keeping.domain.payment.transactions.repository.TransactionItemRepository;
import com.ssafy.keeping.domain.payment.transactions.repository.TransactionRepository;
import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.store.repository.StoreRepository;
import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.user.customer.repository.CustomerRepository;
import com.ssafy.keeping.domain.wallet.model.Wallet;
import com.ssafy.keeping.domain.wallet.model.WalletStoreBalance;
import com.ssafy.keeping.domain.wallet.repository.WalletLotMoveRepository;
import com.ssafy.keeping.domain.wallet.repository.WalletRepository;
import com.ssafy.keeping.domain.wallet.repository.WalletStoreBalanceRepository;
import com.ssafy.keeping.domain.wallet.repository.WalletStoreLotRepository;
import com.ssafy.keeping.domain.wallet.service.WalletLedgerService;
import com.ssafy.keeping.global.config.WalletLedgerProperties;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import com.ssafy.keeping.global.metrics.LedgerMetrics;
import jakarta.persistence.LockTimeoutException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InternalWalletServiceTest {

  @Mock private WalletRepository walletRepository;
  @Mock private WalletStoreBalanceRepository balanceRepository;
  @Mock private StoreRepository storeRepository;
  @Mock private CustomerRepository customerRepository;
  @Mock private TransactionRepository transactionRepository;
  @Mock private TransactionItemRepository transactionItemRepository;
  @Mock private MenuRepository menuRepository;
  @Mock private WalletStoreLotRepository walletStoreLotRepository;
  @Mock private WalletLotMoveRepository walletLotMoveRepository;
  @Mock private WalletLedgerService walletLedgerService;
  @Mock private WalletLedgerProperties walletLedgerProperties;
  @Mock private LedgerMetrics ledgerMetrics;
  @Mock private IdempotencyService idempotencyService;
  @Mock private ObjectMapper canonicalObjectMapper;

  private InternalWalletService sut;

  @BeforeEach
  void setUp() {
    sut =
        new InternalWalletService(
            walletRepository,
            balanceRepository,
            storeRepository,
            customerRepository,
            transactionRepository,
            transactionItemRepository,
            menuRepository,
            walletStoreLotRepository,
            walletLotMoveRepository,
            walletLedgerService,
            walletLedgerProperties,
            ledgerMetrics,
            idempotencyService,
            canonicalObjectMapper);
  }

  // ===== processRefund 테스트 (PR #20: R-1 ~ R-5, R-7) =====

  @Nested
  @DisplayName("processRefund")
  class ProcessRefund {

    private final Long walletId = 1L;
    private final Long storeId = 10L;
    private final Long amount = 3000L;
    private final Long originalTxId = 100L;

    private Wallet wallet;
    private Store store;
    private Customer customer;
    private Transaction originalTx;

    @BeforeEach
    void setUpRefund() {
      wallet = Wallet.builder().walletId(walletId).build();
      store = Store.builder().storeId(storeId).build();
      customer = Customer.builder().customerId(5L).build();
      originalTx =
          Transaction.builder()
              .transactionId(originalTxId)
              .wallet(wallet)
              .customer(customer)
              .store(store)
              .amount(amount)
              .build();
    }

    @Test
    @DisplayName("R-1: originalTransactionId가 null이면 REFUND_ORIGINAL_TX_REQUIRED 예외")
    void throws_when_originalTransactionId_is_null() {
      RefundRequest request =
          RefundRequest.builder()
              .storeId(storeId)
              .amount(amount)
              .originalTransactionId(null)
              .reason("test")
              .build();

      assertThatThrownBy(() -> sut.processRefund(walletId, request))
          .isInstanceOf(CustomException.class)
          .satisfies(
              e -> assertThat(((CustomException) e).getErrorCode())
                  .isEqualTo(ErrorCode.REFUND_ORIGINAL_TX_REQUIRED));
    }

    @Test
    @DisplayName("R-2: 원거래의 walletId가 요청 walletId와 불일치하면 실패 응답")
    void returns_failed_when_wallet_mismatch() {
      Wallet otherWallet = Wallet.builder().walletId(999L).build();
      Transaction mismatchedTx =
          Transaction.builder()
              .transactionId(originalTxId)
              .wallet(otherWallet)
              .customer(customer)
              .store(store)
              .amount(amount)
              .build();

      RefundRequest request =
          RefundRequest.builder()
              .storeId(storeId)
              .amount(amount)
              .originalTransactionId(originalTxId)
              .reason("test")
              .build();

      when(walletRepository.findById(walletId)).thenReturn(Optional.of(wallet));
      when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));
      when(transactionRepository.findById(originalTxId)).thenReturn(Optional.of(mismatchedTx));

      RefundResponse response = sut.processRefund(walletId, request);

      assertThat(response.isSuccess()).isFalse();
      assertThat(response.getMessage()).contains("지갑 ID가 일치하지 않습니다");
    }

    @Test
    @DisplayName("R-3: 정상 환불 — customer가 원거래에서 전파되고 lot 복원 후 성공")
    void happy_path_propagates_customer_and_restores_lots() {
      RefundRequest request =
          RefundRequest.builder()
              .storeId(storeId)
              .amount(amount)
              .originalTransactionId(originalTxId)
              .reason("결제 복구")
              .build();

      Transaction savedRefundTx =
          Transaction.builder()
              .transactionId(200L)
              .wallet(wallet)
              .customer(customer)
              .store(store)
              .amount(amount)
              .build();

      when(walletRepository.findById(walletId)).thenReturn(Optional.of(wallet));
      when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));
      when(transactionRepository.findById(originalTxId)).thenReturn(Optional.of(originalTx));
      when(balanceRepository.lockByWalletIdAndStoreId(walletId, storeId))
          .thenReturn(Optional.of(WalletStoreBalance.builder().balance(0L).build()));
      when(transactionRepository.save(any(Transaction.class))).thenReturn(savedRefundTx);
      when(walletLedgerService.restoreLotsByOriginalTx(eq(originalTx), eq(savedRefundTx)))
          .thenReturn(amount);

      RefundResponse response = sut.processRefund(walletId, request);

      assertThat(response.isSuccess()).isTrue();
      assertThat(response.getRefundTransactionId()).isEqualTo(200L);

      // customer가 원거래에서 전파되었는지 확인
      ArgumentCaptor<Transaction> txCaptor = ArgumentCaptor.forClass(Transaction.class);
      verify(transactionRepository).save(txCaptor.capture());
      assertThat(txCaptor.getValue().getCustomer()).isEqualTo(customer);
    }

    @Test
    @DisplayName("R-4: walletLedgerService.restoreLotsByOriginalTx가 호출된다")
    void calls_restoreLotsByOriginalTx() {
      RefundRequest request =
          RefundRequest.builder()
              .storeId(storeId)
              .amount(amount)
              .originalTransactionId(originalTxId)
              .reason("test")
              .build();

      Transaction savedRefundTx =
          Transaction.builder().transactionId(200L).wallet(wallet).store(store).build();

      when(walletRepository.findById(walletId)).thenReturn(Optional.of(wallet));
      when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));
      when(transactionRepository.findById(originalTxId)).thenReturn(Optional.of(originalTx));
      when(balanceRepository.lockByWalletIdAndStoreId(walletId, storeId))
          .thenReturn(Optional.of(WalletStoreBalance.builder().balance(0L).build()));
      when(transactionRepository.save(any(Transaction.class))).thenReturn(savedRefundTx);
      when(walletLedgerService.restoreLotsByOriginalTx(eq(originalTx), eq(savedRefundTx)))
          .thenReturn(amount);

      sut.processRefund(walletId, request);

      verify(walletLedgerService).restoreLotsByOriginalTx(originalTx, savedRefundTx);
    }

    @Test
    @DisplayName("R-5: lot 복원 합계가 환불 금액과 불일치하면 FUNDS_INVARIANT_VIOLATION 예외")
    void throws_when_lot_restore_sum_mismatches_amount() {
      RefundRequest request =
          RefundRequest.builder()
              .storeId(storeId)
              .amount(amount)
              .originalTransactionId(originalTxId)
              .reason("test")
              .build();

      Transaction savedRefundTx =
          Transaction.builder().transactionId(200L).wallet(wallet).store(store).build();

      when(walletRepository.findById(walletId)).thenReturn(Optional.of(wallet));
      when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));
      when(transactionRepository.findById(originalTxId)).thenReturn(Optional.of(originalTx));
      when(balanceRepository.lockByWalletIdAndStoreId(walletId, storeId))
          .thenReturn(Optional.of(WalletStoreBalance.builder().balance(0L).build()));
      when(transactionRepository.save(any(Transaction.class))).thenReturn(savedRefundTx);
      when(walletLedgerService.restoreLotsByOriginalTx(eq(originalTx), eq(savedRefundTx)))
          .thenReturn(2000L); // 3000 != 2000

      assertThatThrownBy(() -> sut.processRefund(walletId, request))
          .isInstanceOf(CustomException.class)
          .satisfies(
              e -> assertThat(((CustomException) e).getErrorCode())
                  .isEqualTo(ErrorCode.FUNDS_INVARIANT_VIOLATION));
    }

    @Test
    @DisplayName("R-7: 락 타임아웃 시 실패 응답 반환 (lot 복원 미실행)")
    void returns_failed_on_lock_timeout() {
      RefundRequest request =
          RefundRequest.builder()
              .storeId(storeId)
              .amount(amount)
              .originalTransactionId(originalTxId)
              .reason("test")
              .build();

      when(walletRepository.findById(walletId)).thenReturn(Optional.of(wallet));
      when(storeRepository.findById(storeId)).thenReturn(Optional.of(store));
      when(transactionRepository.findById(originalTxId)).thenReturn(Optional.of(originalTx));
      when(balanceRepository.lockByWalletIdAndStoreId(walletId, storeId))
          .thenThrow(new LockTimeoutException());

      RefundResponse response = sut.processRefund(walletId, request);

      assertThat(response.isSuccess()).isFalse();
      assertThat(response.getMessage()).contains("다른 결제가 진행 중");
      verify(walletLedgerService, never()).restoreLotsByOriginalTx(any(), any());
      verify(transactionRepository, never()).save(any(Transaction.class));
    }
  }

  // ===== restore 제거 확인 (PR #22: D-1, D-2) =====

  @Nested
  @DisplayName("restore 제거 검증")
  class RestoreRemoval {

    @Test
    @DisplayName("D-1: InternalWalletService에 restore 메서드가 존재하지 않는다")
    void restore_method_does_not_exist() throws Exception {
      assertThatThrownBy(
              () ->
                  InternalWalletService.class.getDeclaredMethod(
                      "restore", Long.class, Long.class, Long.class))
          .isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    @DisplayName("D-2: InternalWalletController에 RestoreRequest 내부 클래스가 존재하지 않는다")
    void restoreRequest_inner_class_does_not_exist() {
      Class<?>[] declaredClasses =
          com.ssafy.keeping.domain.internal.controller.InternalWalletController.class
              .getDeclaredClasses();
      for (Class<?> c : declaredClasses) {
        assertThat(c.getSimpleName()).isNotEqualTo("RestoreRequest");
      }
    }
  }
}
