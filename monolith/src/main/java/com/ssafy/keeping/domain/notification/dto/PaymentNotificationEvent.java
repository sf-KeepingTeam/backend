package com.ssafy.keeping.domain.notification.dto;

import java.time.OffsetDateTime;

/**
 * Kafka 토픽 {@code keeping.payment.notification.v1} 역직렬화용 DTO.
 *
 * <p>파생 게터를 추가하지 마라 — Jackson이 프로퍼티로 인식하면 직렬화 사고가 난다 (v2 QrToken.isExpired 사례).
 *
 * <p>{@code FAIL_ON_UNKNOWN_PROPERTIES=false} 로 파싱하므로, 프로듀서가 필드를 추가해도 컨슈머가 깨지지
 * 않는다.
 */
public record PaymentNotificationEvent(
    String eventId,
    String eventType,
    OffsetDateTime occurredAt,
    Long intentId,
    Long customerId,
    Long storeId,
    Long amount) {}
