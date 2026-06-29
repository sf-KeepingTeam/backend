package com.ssafy.keeping.domain.payment.toss;

import com.ssafy.keeping.domain.payment.toss.dto.TossCancelRequest;
import com.ssafy.keeping.domain.payment.toss.dto.TossCancelResponse;
import com.ssafy.keeping.domain.payment.toss.dto.TossPaymentConfirmRequest;
import com.ssafy.keeping.domain.payment.toss.dto.TossPaymentConfirmResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * 토스페이먼츠 Stub 클라이언트.
 * 실제 토스 API를 호출하지 않고 항상 성공 응답을 반환한다.
 * payment.toss.mode 가 없거나 stub 일 때 활성화된다 (기본값).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "payment.toss.mode", havingValue = "stub", matchIfMissing = true)
public class StubTossPaymentClient implements TossPaymentClient {

    @Override
    public TossPaymentConfirmResponse confirmPayment(TossPaymentConfirmRequest request) {
        log.info("[TossStub] 결제 승인 stub 처리 - orderId: {}, amount: {}",
                request.getOrderId(), request.getAmount());

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.of("+09:00"));

        TossPaymentConfirmResponse response = new TossPaymentConfirmResponse();
        response.setPaymentKey(request.getPaymentKey() != null
                ? request.getPaymentKey()
                : "stub_pk_" + UUID.randomUUID());
        response.setOrderId(request.getOrderId());
        response.setOrderName("stub-order");
        response.setStatus("DONE");
        response.setTotalAmount(request.getAmount());
        response.setBalanceAmount(request.getAmount());
        response.setMethod("카드");
        response.setRequestedAt(now);
        response.setApprovedAt(now);

        TossPaymentConfirmResponse.CardInfo card = new TossPaymentConfirmResponse.CardInfo();
        card.setCompany("stub카드");
        card.setNumber("****-****-****-0000");
        card.setInstallmentPlanMonths(0);
        card.setApproveNo("00000000");
        card.setUseCardPoint(false);
        card.setCardType("신용");
        card.setOwnerType("개인");
        card.setAcquireStatus("READY");
        card.setIssuerCode("00");
        card.setAcquirerCode("00");
        response.setCard(card);

        return response;
    }

    @Override
    public TossCancelResponse cancelPayment(String paymentKey, TossCancelRequest request) {
        log.info("[TossStub] 결제 취소 stub 처리 - paymentKey: {}, cancelAmount: {}",
                paymentKey, request.getCancelAmount());

        // 실패 주입: cancelReason에 FORCE_FAIL 포함 시 영구실패 응답 반환
        if (request.getCancelReason() != null && request.getCancelReason().contains("FORCE_FAIL")) {
            log.warn("[TossStub] 영구실패 주입 활성화 - FORCE_FAIL 마커 감지");
            TossCancelResponse failResponse = new TossCancelResponse();
            failResponse.setCode("FORCE_FAIL");
            failResponse.setMessage("Stub 실패 주입: 테스트용 강제 실패");
            return failResponse;
        }

        // 실패 주입: cancelReason에 FORCE_TIMEOUT 포함 시 일시실패(네트워크 타임아웃) 모사
        if (request.getCancelReason() != null && request.getCancelReason().contains("FORCE_TIMEOUT")) {
            log.warn("[TossStub] 일시실패 주입 활성화 - FORCE_TIMEOUT 마커 감지");
            throw new RuntimeException("Stub: 네트워크 타임아웃 시뮬레이션");
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.of("+09:00"));

        TossCancelResponse response = new TossCancelResponse();
        response.setPaymentKey(paymentKey);
        response.setOrderId("stub-order-" + UUID.randomUUID().toString().substring(0, 8));
        response.setStatus("CANCELED");
        response.setTotalAmount(request.getCancelAmount() != null ? request.getCancelAmount() : 0L);
        response.setBalanceAmount(0L);

        TossCancelResponse.CancelInfo cancelInfo = new TossCancelResponse.CancelInfo();
        cancelInfo.setCancelAmount(request.getCancelAmount());
        cancelInfo.setCancelReason(request.getCancelReason());
        cancelInfo.setCanceledAt(now);
        cancelInfo.setTransactionKey("stub_txk_" + UUID.randomUUID().toString().substring(0, 8));
        response.setCancels(List.of(cancelInfo));

        return response;
    }
}
