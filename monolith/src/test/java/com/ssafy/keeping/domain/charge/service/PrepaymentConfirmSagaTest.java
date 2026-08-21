package com.ssafy.keeping.domain.charge.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ssafy.keeping.domain.charge.dto.ConfirmPrepareResult;
import com.ssafy.keeping.domain.charge.dto.request.PrepaymentConfirmRequest;
import com.ssafy.keeping.domain.charge.dto.response.PrepaymentResponseDto;
import com.ssafy.keeping.domain.idempotency.model.IdempotentResult;
import com.ssafy.keeping.domain.payment.toss.TossPaymentClient;
import com.ssafy.keeping.domain.payment.toss.dto.TossPaymentConfirmRequest;
import com.ssafy.keeping.domain.payment.toss.dto.TossPaymentConfirmResponse;
import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.wallet.model.Wallet;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * PrepaymentService saga 오케스트레이터 단위 테스트. Phase A/B(PrepaymentConfirmService),
 * 보상(PrepaymentCompensationService), TossPaymentClient를 mock하여 오케스트레이션 로직만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class PrepaymentConfirmSagaTest {

  @Mock private TossPaymentClient tossPaymentClient;
  @Mock private PrepaymentConfirmService prepaymentConfirmService;
  @Mock private PrepaymentCompensationService prepaymentCompensationService;

  @Mock
  private com.ssafy.keeping.domain.user.customer.repository.CustomerRepository customerRepository;

  @Mock private com.ssafy.keeping.domain.store.repository.StoreRepository storeRepository;

  @Mock
  private com.ssafy.keeping.domain.charge.repository.PaymentReservationRepository
      paymentReservationRepository;

  @InjectMocks private PrepaymentService prepaymentService;

  private PrepaymentConfirmRequest confirmRequest;
  private ConfirmPrepareResult prepareResult;
  private Store mockStore;
  private Wallet mockWallet;

  @BeforeEach
  void setUp() {
    confirmRequest =
        PrepaymentConfirmRequest.builder()
            .paymentKey("pk_test_123")
            .orderId("ORDER_TEST_001")
            .amount(50000L)
            .build();

    mockStore = Store.builder().storeId(1L).storeName("테스트 매장").build();
    mockWallet = Wallet.builder().walletId(1L).build();

    prepareResult =
        ConfirmPrepareResult.builder()
            .reservationId(100L)
            .orderId("ORDER_TEST_001")
            .paymentKey("pk_test_123")
            .amount(50000L)
            .store(mockStore)
            .wallet(mockWallet)
            .build();
  }

  @Test
  @DisplayName("케이스1: 정상충전 — Phase A + 토스 confirm + Phase B 모두 성공 → created 반환")
  void confirmPayment_success() {
    // given
    when(prepaymentConfirmService.prepareConfirm(eq(1L), eq(1L), any())).thenReturn(prepareResult);

    TossPaymentConfirmResponse tossResponse = new TossPaymentConfirmResponse();
    tossResponse.setStatus("DONE");
    tossResponse.setPaymentKey("pk_test_123");
    when(tossPaymentClient.confirmPayment(any(TossPaymentConfirmRequest.class)))
        .thenReturn(tossResponse);

    PrepaymentResponseDto creditResult =
        PrepaymentResponseDto.builder()
            .transactionId(200L)
            .transactionUniqueNo("pk_test_123")
            .storeId(1L)
            .storeName("테스트 매장")
            .paymentAmount(50000L)
            .bonusPercentage(0)
            .bonusAmount(0L)
            .totalPoints(50000L)
            .transactionTime(LocalDateTime.now())
            .remainingBalance(50000L)
            .build();
    when(prepaymentConfirmService.creditPoints(
            eq("ORDER_TEST_001"),
            eq("pk_test_123"),
            eq(mockWallet),
            eq(mockStore),
            eq(50000L),
            any()))
        .thenReturn(creditResult);

    // when
    IdempotentResult<PrepaymentResponseDto> result =
        prepaymentService.confirmPayment(1L, 1L, confirmRequest);

    // then
    assertThat(result.getBody().getTransactionId()).isEqualTo(200L);
    assertThat(result.getBody().getPaymentAmount()).isEqualTo(50000L);

    verify(prepaymentConfirmService).prepareConfirm(1L, 1L, confirmRequest);
    verify(tossPaymentClient).confirmPayment(any());
    verify(prepaymentConfirmService)
        .creditPoints(anyString(), anyString(), any(), any(), anyLong(), any());
    verifyNoInteractions(prepaymentCompensationService);
  }

  @Test
  @DisplayName("케이스2: 토스 confirm 실패 → markReservationFailed 호출 + PAYMENT_CONFIRM_FAILED 예외")
  void confirmPayment_tossConfirmFails_reservationFailed() {
    // given
    when(prepaymentConfirmService.prepareConfirm(eq(1L), eq(1L), any())).thenReturn(prepareResult);

    TossPaymentConfirmResponse failResponse = new TossPaymentConfirmResponse();
    failResponse.setCode("REJECT_CARD_COMPANY");
    failResponse.setMessage("카드사 거절");
    when(tossPaymentClient.confirmPayment(any(TossPaymentConfirmRequest.class)))
        .thenReturn(failResponse);

    // when & then
    assertThatThrownBy(() -> prepaymentService.confirmPayment(1L, 1L, confirmRequest))
        .isInstanceOf(CustomException.class)
        .extracting(e -> ((CustomException) e).getErrorCode())
        .isEqualTo(ErrorCode.PAYMENT_CONFIRM_FAILED);

    verify(prepaymentCompensationService).markReservationFailed(100L);
    verify(prepaymentConfirmService, never())
        .creditPoints(any(), any(), any(), any(), anyLong(), any());
    verify(prepaymentCompensationService, never()).compensate(anyLong(), anyString(), anyString());
  }

  @Test
  @DisplayName(
      "케이스3: 적립 실패(Phase B) → compensate 호출(토스 cancel + 예약 FAILED) + PAYMENT_CONFIRM_FAILED 예외")
  void confirmPayment_creditFails_compensationTriggered() {
    // given
    when(prepaymentConfirmService.prepareConfirm(eq(1L), eq(1L), any())).thenReturn(prepareResult);

    TossPaymentConfirmResponse tossResponse = new TossPaymentConfirmResponse();
    tossResponse.setStatus("DONE");
    tossResponse.setPaymentKey("pk_test_123");
    when(tossPaymentClient.confirmPayment(any(TossPaymentConfirmRequest.class)))
        .thenReturn(tossResponse);

    when(prepaymentConfirmService.creditPoints(
            eq("ORDER_TEST_001"),
            eq("pk_test_123"),
            eq(mockWallet),
            eq(mockStore),
            eq(50000L),
            any()))
        .thenThrow(new RuntimeException("DB 적립 실패"));

    // when & then
    assertThatThrownBy(() -> prepaymentService.confirmPayment(1L, 1L, confirmRequest))
        .isInstanceOf(CustomException.class)
        .extracting(e -> ((CustomException) e).getErrorCode())
        .isEqualTo(ErrorCode.PAYMENT_CONFIRM_FAILED);

    // 보상이 호출되었는지 확인 (토스 cancel + 예약 FAILED)
    verify(prepaymentCompensationService).compensate(100L, "pk_test_123", "적립 실패로 인한 자동 취소");
    // markReservationFailed는 호출되지 않음 (토스 confirm은 성공했으므로)
    verify(prepaymentCompensationService, never()).markReservationFailed(anyLong());
  }

  @Test
  @DisplayName("ALREADY_PROCESSED 응답은 성공으로 판정 → Phase B로 진행")
  void confirmPayment_alreadyProcessed_treatedAsSuccess() {
    // given
    when(prepaymentConfirmService.prepareConfirm(eq(1L), eq(1L), any())).thenReturn(prepareResult);

    TossPaymentConfirmResponse alreadyProcessedResponse = new TossPaymentConfirmResponse();
    alreadyProcessedResponse.setCode("ALREADY_PROCESSED_PAYMENT");
    alreadyProcessedResponse.setMessage("이미 처리된 결제입니다.");
    alreadyProcessedResponse.setPaymentKey("pk_test_123");
    when(tossPaymentClient.confirmPayment(any(TossPaymentConfirmRequest.class)))
        .thenReturn(alreadyProcessedResponse);

    PrepaymentResponseDto creditResult =
        PrepaymentResponseDto.builder()
            .transactionId(200L)
            .transactionUniqueNo("pk_test_123")
            .storeId(1L)
            .storeName("테스트 매장")
            .paymentAmount(50000L)
            .bonusPercentage(0)
            .bonusAmount(0L)
            .totalPoints(50000L)
            .transactionTime(LocalDateTime.now())
            .remainingBalance(50000L)
            .build();
    when(prepaymentConfirmService.creditPoints(
            eq("ORDER_TEST_001"),
            eq("pk_test_123"),
            eq(mockWallet),
            eq(mockStore),
            eq(50000L),
            any()))
        .thenReturn(creditResult);

    // when
    IdempotentResult<PrepaymentResponseDto> result =
        prepaymentService.confirmPayment(1L, 1L, confirmRequest);

    // then — Phase B 정상 진행, 보상 미호출
    assertThat(result.getBody().getTransactionId()).isEqualTo(200L);
    verify(prepaymentConfirmService)
        .creditPoints(anyString(), anyString(), any(), any(), anyLong(), any());
    verifyNoInteractions(prepaymentCompensationService);
  }
}
