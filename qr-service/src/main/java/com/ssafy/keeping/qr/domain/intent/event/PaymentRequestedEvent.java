package com.ssafy.keeping.qr.domain.intent.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

/**
 * 결제 의도(initiate) 생성 완료 이벤트.
 *
 * <p>커밋 후 알림(PAYMENT_REQUEST) 발송 트리거.
 * 엔티티를 담지 않고 값만 전달한다 — 커밋 후 lazy 로딩 방지.
 */
@Getter
@RequiredArgsConstructor
@ToString
public class PaymentRequestedEvent {

    private final Long intentId;
    private final Long customerId;
    private final Long storeId;
    private final Long amount;
}
