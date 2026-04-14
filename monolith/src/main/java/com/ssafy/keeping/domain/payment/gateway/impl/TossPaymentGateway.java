package com.ssafy.keeping.domain.payment.gateway.impl;

import com.ssafy.keeping.domain.payment.gateway.PaymentGateway;
import com.ssafy.keeping.domain.payment.gateway.PaymentProvider;
import com.ssafy.keeping.domain.payment.gateway.dto.CancelRequest;
import com.ssafy.keeping.domain.payment.gateway.dto.CancelResult;
import com.ssafy.keeping.domain.payment.gateway.dto.PaymentRequest;
import com.ssafy.keeping.domain.payment.gateway.dto.PaymentResult;
import com.ssafy.keeping.domain.payment.toss.TossPaymentClient;
import com.ssafy.keeping.domain.payment.toss.dto.TossCancelRequest;
import com.ssafy.keeping.domain.payment.toss.dto.TossCancelResponse;
import com.ssafy.keeping.domain.payment.toss.dto.TossPaymentConfirmRequest;
import com.ssafy.keeping.domain.payment.toss.dto.TossPaymentConfirmResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 토스페이먼츠 결제 게이트웨이 구현체
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TossPaymentGateway implements PaymentGateway {

    private final TossPaymentClient tossPaymentClient;

    @Override
    public PaymentResult processPayment(PaymentRequest request) {
        log.info("[TossGateway] 결제 처리 시작 - orderId: {}, amount: {}",
                request.getOrderId(), request.getAmount());

        // 공통 요청 → 토스 요청으로 변환
        TossPaymentConfirmRequest tossRequest = TossPaymentConfirmRequest.builder()
                .paymentKey(request.getPaymentKey())
                .orderId(request.getOrderId())
                .amount(request.getAmount())
                .build();

        // 토스 API 호출
        TossPaymentConfirmResponse tossResponse = tossPaymentClient.confirmPayment(tossRequest);

        // 토스 응답 → 공통 결과로 변환
        if (tossResponse.isSuccess()) {
            LocalDateTime approvedAt = tossResponse.getApprovedAt() != null
                    ? tossResponse.getApprovedAt().atZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDateTime()
                    : LocalDateTime.now();

            PaymentResult.PaymentResultBuilder resultBuilder = PaymentResult.builder()
                    .success(true)
                    .paymentKey(tossResponse.getPaymentKey())
                    .orderId(tossResponse.getOrderId())
                    .totalAmount(tossResponse.getTotalAmount())
                    .method(tossResponse.getMethod())
                    .status(tossResponse.getStatus())
                    .approvedAt(approvedAt);

            // 카드 정보 추가
            if (tossResponse.getCard() != null) {
                resultBuilder
                        .cardCompany(tossResponse.getCard().getCompany())
                        .cardNumber(tossResponse.getCard().getNumber());
            }

            return resultBuilder.build();
        } else {
            return PaymentResult.failure(
                    tossResponse.getCode(),
                    tossResponse.getMessage()
            );
        }
    }

    @Override
    public CancelResult cancelPayment(CancelRequest request) {
        log.info("[TossGateway] 결제 취소 시작 - paymentKey: {}, reason: {}",
                request.getPaymentKey(), request.getCancelReason());

        // 공통 요청 → 토스 요청으로 변환
        TossCancelRequest.TossCancelRequestBuilder tossRequestBuilder = TossCancelRequest.builder()
                .cancelReason(request.getCancelReason())
                .cancelAmount(request.getCancelAmount());

        // 환불 계좌 정보가 있으면 추가
        if (request.getRefundBankCode() != null && request.getRefundAccountNumber() != null) {
            tossRequestBuilder.refundReceiveAccount(
                    TossCancelRequest.RefundReceiveAccount.builder()
                            .bank(request.getRefundBankCode())
                            .accountNumber(request.getRefundAccountNumber())
                            .holderName(request.getRefundHolderName())
                            .build()
            );
        }

        TossCancelRequest tossRequest = tossRequestBuilder.build();

        // 토스 API 호출
        TossCancelResponse tossResponse = tossPaymentClient.cancelPayment(
                request.getPaymentKey(),
                tossRequest
        );

        // 토스 응답 → 공통 결과로 변환
        if (tossResponse.isSuccess()) {
            TossCancelResponse.CancelInfo latestCancel = tossResponse.getLatestCancel();

            LocalDateTime canceledAt = latestCancel != null && latestCancel.getCanceledAt() != null
                    ? latestCancel.getCanceledAt().atZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDateTime()
                    : LocalDateTime.now();

            Long cancelAmount = latestCancel != null
                    ? latestCancel.getCancelAmount()
                    : request.getCancelAmount();

            return CancelResult.builder()
                    .success(true)
                    .paymentKey(tossResponse.getPaymentKey())
                    .cancelAmount(cancelAmount)
                    .cancelReason(request.getCancelReason())
                    .canceledAt(canceledAt)
                    .status(tossResponse.getStatus())
                    .build();
        } else {
            return CancelResult.failure(
                    tossResponse.getCode(),
                    tossResponse.getMessage()
            );
        }
    }

    @Override
    public PaymentProvider getProviderType() {
        return PaymentProvider.TOSS;
    }
}
