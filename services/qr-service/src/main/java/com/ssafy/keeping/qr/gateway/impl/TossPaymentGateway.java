package com.ssafy.keeping.qr.gateway.impl;

import com.ssafy.keeping.qr.gateway.PaymentGateway;
import com.ssafy.keeping.qr.gateway.PaymentProvider;
import com.ssafy.keeping.qr.gateway.dto.CancelRequest;
import com.ssafy.keeping.qr.gateway.dto.CancelResult;
import com.ssafy.keeping.qr.gateway.dto.PaymentRequest;
import com.ssafy.keeping.qr.gateway.dto.PaymentResult;
import com.ssafy.keeping.qr.toss.TossPaymentClient;
import com.ssafy.keeping.qr.toss.dto.TossCancelRequest;
import com.ssafy.keeping.qr.toss.dto.TossCancelResponse;
import com.ssafy.keeping.qr.toss.dto.TossPaymentConfirmRequest;
import com.ssafy.keeping.qr.toss.dto.TossPaymentConfirmResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;

@Slf4j
@Component
@RequiredArgsConstructor
public class TossPaymentGateway implements PaymentGateway {

    private final TossPaymentClient tossPaymentClient;

    @Override
    public PaymentResult processPayment(PaymentRequest request) {
        log.info("[TossGateway] 결제 처리 시작 - orderId: {}, amount: {}",
                request.getOrderId(), request.getAmount());

        TossPaymentConfirmRequest tossRequest = TossPaymentConfirmRequest.builder()
                .paymentKey(request.getPaymentKey())
                .orderId(request.getOrderId())
                .amount(request.getAmount())
                .build();

        TossPaymentConfirmResponse tossResponse = tossPaymentClient.confirmPayment(tossRequest);

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

        TossCancelRequest.TossCancelRequestBuilder tossRequestBuilder = TossCancelRequest.builder()
                .cancelReason(request.getCancelReason())
                .cancelAmount(request.getCancelAmount());

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

        TossCancelResponse tossResponse = tossPaymentClient.cancelPayment(
                request.getPaymentKey(),
                tossRequest
        );

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
