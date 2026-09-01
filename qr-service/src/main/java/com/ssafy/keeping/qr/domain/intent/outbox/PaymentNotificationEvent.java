package com.ssafy.keeping.qr.domain.intent.outbox;

import java.time.OffsetDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 아웃박스 payload 에 직렬화되는 결제 알림 이벤트.
 *
 * <p>#53(monolith 컨슈머)이 동일 구조로 역직렬화한다. 사실(fact)만 담고 문구/매장명/점주ID 등 표현 계층
 * 데이터는 넣지 않는다.
 *
 * <p>직렬화에는 {@code @Primary objectMapper()}(JavaTimeModule 등록, WRITE_DATES_AS_TIMESTAMPS=false)를
 * 사용한다. canonicalObjectMapper 는 멱등성 해시 전용이므로 NON_NULL 등이 섞여 부적합.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentNotificationEvent {

  private String eventId;
  private String eventType;
  private OffsetDateTime occurredAt;
  private Long intentId;
  private Long customerId;
  private Long storeId;
  private Long amount;
}
