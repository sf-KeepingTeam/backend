package com.ssafy.keeping.qr.domain.intent.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

/**
 * 결제 승인(approve) 완료 이벤트.
 *
 * <p>커밋 후 알림(PAYMENT_APPROVED) 발송 트리거 — 점주 + 고객 양쪽.
 * 엔티티를 담지 않고 값만 전달한다.
 * {@code storeClient.getStore()} 호출은 리스너가 수행한다.
 */
@Getter
@RequiredArgsConstructor
@ToString
public class PaymentApprovedEvent {

    private final Long intentId;
    private final Long customerId;
    private final Long storeId;
    private final Long amount;
}
