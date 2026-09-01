package com.ssafy.keeping.qr.domain.intent.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PaymentOutboxTest {

  // ──────────────────────────────────────────────
  // O-1: markSent
  // ──────────────────────────────────────────────
  @Test
  @DisplayName("O-1: markSent 호출 시 status=SENT, sentAt 설정")
  void markSent_setsStatusAndSentAt() {
    PaymentOutbox outbox = PaymentOutbox.builder().eventId(UUID.randomUUID().toString()).build();

    LocalDateTime now = LocalDateTime.now();
    outbox.markSent(now);

    assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.SENT);
    assertThat(outbox.getSentAt()).isEqualTo(now);
  }

  // ──────────────────────────────────────────────
  // O-2: markRetry
  // ──────────────────────────────────────────────
  @Test
  @DisplayName("O-2: markRetry 호출 시 retryCount +1, lastError 설정, status 는 PENDING 유지")
  void markRetry_incrementsCountAndKeepsPending() {
    PaymentOutbox outbox = PaymentOutbox.builder().eventId(UUID.randomUUID().toString()).build();

    outbox.markRetry("timeout");
    assertThat(outbox.getRetryCount()).isEqualTo(1);
    assertThat(outbox.getLastError()).isEqualTo("timeout");
    assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);

    outbox.markRetry("second failure");
    assertThat(outbox.getRetryCount()).isEqualTo(2);
    assertThat(outbox.getLastError()).isEqualTo("second failure");
    assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
  }

  // ──────────────────────────────────────────────
  // O-3: markFailed
  // ──────────────────────────────────────────────
  @Test
  @DisplayName("O-3: markFailed 호출 시 status=FAILED")
  void markFailed_setsStatusToFailed() {
    PaymentOutbox outbox = PaymentOutbox.builder().eventId(UUID.randomUUID().toString()).build();

    outbox.markFailed("max retry exceeded");

    assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.FAILED);
    assertThat(outbox.getLastError()).isEqualTo("max retry exceeded");
  }

  // ──────────────────────────────────────────────
  // O-4: 페이로드 직렬화 — 파생 프로퍼티 없음 + occurredAt ISO-8601
  // ──────────────────────────────────────────────
  @Test
  @DisplayName("O-4: PaymentNotificationEvent 직렬화 시 eventId, occurredAt(ISO-8601 오프셋) 존재, 파생 프로퍼티 없음")
  void serialize_containsExpectedFieldsOnly() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    PaymentNotificationEvent event =
        PaymentNotificationEvent.builder()
            .eventId("evt-001")
            .eventType("PAYMENT_APPROVED")
            .occurredAt(OffsetDateTime.of(2026, 8, 24, 12, 0, 0, 0, ZoneOffset.of("+09:00")))
            .intentId(100L)
            .customerId(200L)
            .storeId(300L)
            .amount(5000L)
            .build();

    String json = mapper.writeValueAsString(event);

    // 필수 필드 존재
    assertThat(json).contains("\"eventId\"");
    assertThat(json).contains("\"occurredAt\"");
    // ISO-8601 오프셋 포함
    assertThat(json).contains("+09:00");

    // 파생 프로퍼티가 없는지 확인: 정확히 7개 필드만
    @SuppressWarnings("unchecked")
    Map<String, Object> map = mapper.readValue(json, Map.class);
    assertThat(map).hasSize(7);
    assertThat(map.keySet())
        .containsExactlyInAnyOrder(
            "eventId", "eventType", "occurredAt", "intentId", "customerId", "storeId", "amount");
  }

  // ──────────────────────────────────────────────
  // O-5: 페이로드 역직렬화 — 모르는 필드 무시
  // ──────────────────────────────────────────────
  @Test
  @DisplayName("O-5: 모르는 필드가 있어도 역직렬화 예외 없음")
  void deserialize_ignoresUnknownFields() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    mapper.registerModule(new JavaTimeModule());
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    String json =
        "{\"eventId\":\"evt-001\",\"eventType\":\"PAYMENT_APPROVED\","
            + "\"occurredAt\":\"2026-08-24T12:00:00+09:00\","
            + "\"intentId\":100,\"customerId\":200,\"storeId\":300,\"amount\":5000,"
            + "\"unknownField\":\"should be ignored\"}";

    PaymentNotificationEvent event = mapper.readValue(json, PaymentNotificationEvent.class);

    assertThat(event.getEventId()).isEqualTo("evt-001");
    assertThat(event.getEventType()).isEqualTo("PAYMENT_APPROVED");
    assertThat(event.getIntentId()).isEqualTo(100L);
  }
}
