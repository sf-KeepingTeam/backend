package com.ssafy.keeping.domain.charge.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.ssafy.keeping.domain.charge.constant.RefundStatus;
import com.ssafy.keeping.domain.charge.dto.CancelReclaimResult;
import com.ssafy.keeping.domain.charge.dto.request.CancelRequestDto;
import com.ssafy.keeping.domain.charge.dto.response.CancelResponseDto;
import com.ssafy.keeping.domain.payment.toss.TossPaymentClient;
import com.ssafy.keeping.domain.payment.toss.dto.TossCancelRequest;
import com.ssafy.keeping.domain.payment.toss.dto.TossCancelResponse;
import com.ssafy.keeping.domain.user.customer.repository.CustomerRepository;
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
 * CancelService saga 단위 테스트. Phase A(CancelReclaimService)와 Phase B(TossPaymentClient)를 mock하여 saga
 * 오케스트레이션 로직만 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class CancelServiceSagaTest {

  @Mock private CustomerRepository customerRepository;

  @Mock private TossPaymentClient tossPaymentClient;

  @Mock private CancelReclaimService cancelReclaimService;

  @InjectMocks private CancelService cancelService;

  private CancelRequestDto normalRequest;
  private CancelReclaimResult reclaimResult;

  @BeforeEach
  void setUp() {
    normalRequest =
        CancelRequestDto.builder().paymentKey("pk_test_123").cancelReason("고객 변심").build();

    reclaimResult =
        CancelReclaimResult.builder()
            .cancelTransactionId(100L)
            .transactionUniqueNo("pk_test_123")
            .cancelAmount(50000L)
            .cancelTime(LocalDateTime.now())
            .remainingBalance(0L)
            .paymentKey("pk_test_123")
            .build();
  }

  @Test
  @DisplayName("케이스1: 정상취소 — Phase A 성공 + 토스 환불 성공 → REFUND_DONE, CancelResponseDto 반환")
  void cancelPayment_success_refundDone() {
    // given
    when(cancelReclaimService.reclaimPoints(eq(1L), any(CancelRequestDto.class)))
        .thenReturn(reclaimResult);

    TossCancelResponse successResponse = new TossCancelResponse();
    successResponse.setStatus("CANCELED");
    successResponse.setPaymentKey("pk_test_123");
    when(tossPaymentClient.cancelPayment(eq("pk_test_123"), any(TossCancelRequest.class)))
        .thenReturn(successResponse);

    // when
    CancelResponseDto result = cancelService.cancelPayment(1L, normalRequest);

    // then
    assertThat(result.getCancelTransactionId()).isEqualTo(100L);
    assertThat(result.getCancelAmount()).isEqualTo(50000L);
    assertThat(result.getRemainingBalance()).isEqualTo(0L);

    verify(cancelReclaimService).updateRefundStatus(100L, RefundStatus.REFUND_DONE);
    verify(tossPaymentClient, times(1)).cancelPayment(anyString(), any());
  }

  @Test
  @DisplayName("케이스2: 잔액 부족 — Phase A에서 FUNDS_INSUFFICIENT 예외 → 토스 cancel 미호출")
  void cancelPayment_insufficientBalance_noTossCall() {
    // given
    when(cancelReclaimService.reclaimPoints(eq(1L), any(CancelRequestDto.class)))
        .thenThrow(new CustomException(ErrorCode.FUNDS_INSUFFICIENT));

    // when & then
    assertThatThrownBy(() -> cancelService.cancelPayment(1L, normalRequest))
        .isInstanceOf(CustomException.class)
        .extracting(e -> ((CustomException) e).getErrorCode())
        .isEqualTo(ErrorCode.FUNDS_INSUFFICIENT);

    verifyNoInteractions(tossPaymentClient);
    verify(cancelReclaimService, never()).updateRefundStatus(anyLong(), any());
  }

  @Test
  @DisplayName("케이스3: 토스 일시실패(타임아웃) — 3회 재시도 후 REFUND_PENDING 예외 throw")
  void cancelPayment_transientFailure_refundPending() {
    // given
    when(cancelReclaimService.reclaimPoints(eq(1L), any(CancelRequestDto.class)))
        .thenReturn(reclaimResult);

    when(tossPaymentClient.cancelPayment(eq("pk_test_123"), any(TossCancelRequest.class)))
        .thenThrow(new RuntimeException("네트워크 타임아웃"));

    // when & then
    assertThatThrownBy(() -> cancelService.cancelPayment(1L, normalRequest))
        .isInstanceOf(CustomException.class)
        .extracting(e -> ((CustomException) e).getErrorCode())
        .isEqualTo(ErrorCode.REFUND_PENDING);

    // 토스 3회 호출 확인 (재시도)
    verify(tossPaymentClient, times(3)).cancelPayment(anyString(), any());

    // REFUND_PENDING 상태이므로 updateRefundStatus는 호출되지 않음 (기본값 유지)
    verify(cancelReclaimService, never()).updateRefundStatus(anyLong(), any());
  }

  @Test
  @DisplayName("ALREADY_CANCELED 응답은 성공(REFUND_DONE)으로 처리 — 이중환불 방지")
  void cancelPayment_alreadyCanceled_treatedAsSuccess() {
    // given
    when(cancelReclaimService.reclaimPoints(eq(1L), any(CancelRequestDto.class)))
        .thenReturn(reclaimResult);

    TossCancelResponse alreadyCanceledResponse = new TossCancelResponse();
    alreadyCanceledResponse.setCode("ALREADY_CANCELED");
    alreadyCanceledResponse.setMessage("이미 취소된 결제입니다.");
    when(tossPaymentClient.cancelPayment(eq("pk_test_123"), any(TossCancelRequest.class)))
        .thenReturn(alreadyCanceledResponse);

    // when
    CancelResponseDto result = cancelService.cancelPayment(1L, normalRequest);

    // then
    assertThat(result.getCancelTransactionId()).isEqualTo(100L);
    verify(cancelReclaimService).updateRefundStatus(100L, RefundStatus.REFUND_DONE);
    verify(tossPaymentClient, times(1)).cancelPayment(anyString(), any());
  }

  @Test
  @DisplayName("ALREADY_CANCELED_PAYMENT 응답도 성공(REFUND_DONE)으로 처리 — startsWith 매칭 검증")
  void cancelPayment_alreadyCanceledPayment_treatedAsSuccess() {
    // given
    when(cancelReclaimService.reclaimPoints(eq(1L), any(CancelRequestDto.class)))
        .thenReturn(reclaimResult);

    TossCancelResponse alreadyCanceledResponse = new TossCancelResponse();
    alreadyCanceledResponse.setCode("ALREADY_CANCELED_PAYMENT");
    alreadyCanceledResponse.setMessage("이미 취소된 결제입니다.");
    when(tossPaymentClient.cancelPayment(eq("pk_test_123"), any(TossCancelRequest.class)))
        .thenReturn(alreadyCanceledResponse);

    // when
    CancelResponseDto result = cancelService.cancelPayment(1L, normalRequest);

    // then
    assertThat(result.getCancelTransactionId()).isEqualTo(100L);
    verify(cancelReclaimService).updateRefundStatus(100L, RefundStatus.REFUND_DONE);
    // 영구실패로 처리되지 않음을 검증
    verify(cancelReclaimService, never())
        .updateRefundStatus(eq(100L), eq(RefundStatus.REFUND_PERMANENT_FAILED));
    verify(tossPaymentClient, times(1)).cancelPayment(anyString(), any());
  }

  @Test
  @DisplayName("영구실패(NOT_CANCELABLE) → REFUND_FAILED_NEEDS_SUPPORT 예외 throw")
  void cancelPayment_permanentFailure_throwsException() {
    // given
    when(cancelReclaimService.reclaimPoints(eq(1L), any(CancelRequestDto.class)))
        .thenReturn(reclaimResult);

    TossCancelResponse permanentFailResponse = new TossCancelResponse();
    permanentFailResponse.setCode("NOT_CANCELABLE_PAYMENT");
    permanentFailResponse.setMessage("취소 불가능한 결제입니다.");
    when(tossPaymentClient.cancelPayment(eq("pk_test_123"), any(TossCancelRequest.class)))
        .thenReturn(permanentFailResponse);

    // when & then
    assertThatThrownBy(() -> cancelService.cancelPayment(1L, normalRequest))
        .isInstanceOf(CustomException.class)
        .extracting(e -> ((CustomException) e).getErrorCode())
        .isEqualTo(ErrorCode.REFUND_FAILED_NEEDS_SUPPORT);

    verify(cancelReclaimService).updateRefundStatus(100L, RefundStatus.REFUND_PERMANENT_FAILED);
    verify(tossPaymentClient, times(1)).cancelPayment(anyString(), any());
  }
}
