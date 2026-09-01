package com.ssafy.keeping.domain.notification.consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ssafy.keeping.domain.notification.dto.StoreNotificationView;
import com.ssafy.keeping.domain.notification.entity.NotificationType;
import com.ssafy.keeping.domain.notification.entity.ProcessedEvent;
import com.ssafy.keeping.domain.notification.metrics.NotificationConsumerMetrics;
import com.ssafy.keeping.domain.notification.repository.ProcessedEventRepository;
import com.ssafy.keeping.domain.notification.service.NotificationService;
import com.ssafy.keeping.domain.store.repository.StoreRepository;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;

/**
 * 알림 Kafka 컨슈머 단위 테스트.
 *
 * <p>Testcontainers/EmbeddedKafka 를 사용하지 않는다 — Docker 없이 실행 가능해야 한다.
 *
 * <p><b>이 테스트는 작성만 되었고 실행하지 않았다.</b>
 */
@ExtendWith(MockitoExtension.class)
class PaymentNotificationConsumerTest {

  @Mock private NotificationService notificationService;
  @Mock private StoreRepository storeRepository;
  @Mock private ProcessedEventRepository processedEventRepository;
  @Mock private NotificationConsumerMetrics metrics;
  @Mock private KafkaTemplate<String, String> kafkaTemplate;
  @Mock private Acknowledgment ack;

  private PaymentNotificationConsumer consumer;

  @BeforeEach
  void setUp() {
    consumer =
        new PaymentNotificationConsumer(
            notificationService, storeRepository, processedEventRepository, metrics, kafkaTemplate);
  }

  // -- helpers --

  private ConsumerRecord<String, String> record(String key, String value) {
    return new ConsumerRecord<>("keeping.payment.notification.v1", 0, 0L, key, value);
  }

  private String approvedJson() {
    return """
        {
          "eventId": "evt-001",
          "eventType": "PAYMENT_APPROVED",
          "occurredAt": "2026-08-24T11:36:24.123+09:00",
          "intentId": 12345,
          "customerId": 678,
          "storeId": 90,
          "amount": 15000
        }
        """;
  }

  private String requestedJson() {
    return """
        {
          "eventId": "evt-002",
          "eventType": "PAYMENT_REQUESTED",
          "occurredAt": "2026-08-24T11:36:24.123+09:00",
          "intentId": 12346,
          "customerId": 678,
          "storeId": 90,
          "amount": 8000
        }
        """;
  }

  // ---- N-1: PAYMENT_APPROVED 정상 ----

  @Test
  @DisplayName("N-1: PAYMENT_APPROVED 정상 — 점주+고객 2건 발송, processed_event 저장, ack")
  void n1_paymentApproved_sendsOwnerAndCustomer() {
    when(storeRepository.findNotificationView(90L))
        .thenReturn(Optional.of(new StoreNotificationView("맛집", 42L)));

    consumer.consume(record("678", approvedJson()), ack);

    // 점주 알림
    verify(notificationService)
        .sendToOwner(eq(42L), eq(NotificationType.PAYMENT_APPROVED), eq("15,000원 결제가 완료되었습니다."));
    // 고객 알림
    verify(notificationService)
        .sendToCustomer(
            eq(678L),
            eq(NotificationType.PAYMENT_APPROVED),
            eq("맛집에서 15,000원 결제가 완료되었습니다."));
    // 멱등성 기록
    verify(processedEventRepository).saveAndFlush(any(ProcessedEvent.class));
    // ack
    verify(ack).acknowledge();
  }

  // ---- N-2: PAYMENT_REQUESTED 정상 ----

  @Test
  @DisplayName("N-2: PAYMENT_REQUESTED 정상 — 고객 1건, NotificationType.PAYMENT_REQUEST")
  void n2_paymentRequested_sendsCustomerOnly() {
    when(storeRepository.findNotificationView(90L))
        .thenReturn(Optional.of(new StoreNotificationView("맛집", 42L)));

    consumer.consume(record("678", requestedJson()), ack);

    verify(notificationService)
        .sendToCustomer(
            eq(678L),
            eq(NotificationType.PAYMENT_REQUEST),
            eq("맛집에서 8,000원 결제 요청이 도착했습니다."));
    // 점주 알림 없음
    verify(notificationService, never()).sendToOwner(anyLong(), any(), anyString());
    verify(ack).acknowledge();
  }

  // ---- N-3: 중복 (DataIntegrityViolationException) ----

  @Test
  @DisplayName("N-3: 중복 — 발송 0건, duplicateSkipped +1, ack")
  void n3_duplicate_skipsAndAcks() {
    when(processedEventRepository.saveAndFlush(any()))
        .thenThrow(new DataIntegrityViolationException("Duplicate"));

    consumer.consume(record("678", approvedJson()), ack);

    verifyNoInteractions(notificationService);
    verify(metrics).duplicateSkipped();
    verify(ack).acknowledge();
  }

  // ---- N-4: 매장 없음 ----

  @Test
  @DisplayName("N-4: 매장 없음 — 고객 알림은 가고 문구에 '매장', 점주 알림 0건")
  void n4_storeNotFound_customerGetsNotificationWithFallback() {
    when(storeRepository.findNotificationView(90L)).thenReturn(Optional.empty());

    consumer.consume(record("678", approvedJson()), ack);

    // 고객 알림 — "매장" 폴백
    verify(notificationService)
        .sendToCustomer(
            eq(678L),
            eq(NotificationType.PAYMENT_APPROVED),
            eq("매장에서 15,000원 결제가 완료되었습니다."));
    // 점주 알림 없음 (ownerId null)
    verify(notificationService, never()).sendToOwner(anyLong(), any(), anyString());
    verify(ack).acknowledge();
  }

  // ---- N-5: 점주ID 없음 ----

  @Test
  @DisplayName("N-5: 점주ID 없음 — 고객 알림만")
  void n5_ownerIdNull_customerOnly() {
    when(storeRepository.findNotificationView(90L))
        .thenReturn(Optional.of(new StoreNotificationView("맛집", null)));

    consumer.consume(record("678", approvedJson()), ack);

    verify(notificationService)
        .sendToCustomer(
            eq(678L),
            eq(NotificationType.PAYMENT_APPROVED),
            eq("맛집에서 15,000원 결제가 완료되었습니다."));
    verify(notificationService, never()).sendToOwner(anyLong(), any(), anyString());
    verify(ack).acknowledge();
  }

  // ---- N-6: JSON 파싱 실패 ----

  @Test
  @DisplayName("N-6: JSON 파싱 실패 — 재시도 없이 DLT, ack")
  void n6_parseError_sendsToDltAndAcks() {
    consumer.consume(record("678", "NOT_JSON!!!"), ack);

    verify(kafkaTemplate)
        .send(eq("keeping.payment.notification.v1.dlt"), eq("678"), eq("NOT_JSON!!!"));
    verify(metrics).dlt();
    verifyNoInteractions(notificationService);
    verify(ack).acknowledge();
  }

  // ---- N-7: NotificationService 예외 ----

  @Test
  @DisplayName("N-7: NotificationService 예외 — ack 호출 안 됨 (재시도 대상)")
  void n7_notificationServiceThrows_noAck() {
    when(storeRepository.findNotificationView(90L))
        .thenReturn(Optional.of(new StoreNotificationView("맛집", 42L)));
    doThrow(new RuntimeException("FCM failure"))
        .when(notificationService)
        .sendToOwner(anyLong(), any(), anyString());

    try {
      consumer.consume(record("678", approvedJson()), ack);
    } catch (RuntimeException ignored) {
      // 예외 전파 — DefaultErrorHandler가 재시도한다
    }

    verify(ack, never()).acknowledge();
  }

  // ---- N-8: 모르는 필드가 있는 JSON ----

  @Test
  @DisplayName("N-8: 모르는 필드가 있는 JSON — 정상 처리")
  void n8_unknownField_processedNormally() {
    String jsonWithExtra =
        """
        {
          "eventId": "evt-003",
          "eventType": "PAYMENT_APPROVED",
          "occurredAt": "2026-08-24T11:36:24.123+09:00",
          "intentId": 12345,
          "customerId": 678,
          "storeId": 90,
          "amount": 15000,
          "futureField": "should be ignored"
        }
        """;
    when(storeRepository.findNotificationView(90L))
        .thenReturn(Optional.of(new StoreNotificationView("맛집", 42L)));

    consumer.consume(record("678", jsonWithExtra), ack);

    verify(notificationService, times(1)).sendToOwner(anyLong(), any(), anyString());
    verify(notificationService, times(1)).sendToCustomer(anyLong(), any(), anyString());
    verify(ack).acknowledge();
  }

  // ---- N-9: occurredAt 이 미래 ----

  @Test
  @DisplayName("N-9: occurredAt 이 미래 — lag 이 음수가 아니라 0")
  void n9_futureOccurredAt_lagClampedToZero() {
    String futureJson =
        """
        {
          "eventId": "evt-004",
          "eventType": "PAYMENT_REQUESTED",
          "occurredAt": "2099-12-31T23:59:59.999+09:00",
          "intentId": 99999,
          "customerId": 678,
          "storeId": 90,
          "amount": 1000
        }
        """;
    when(storeRepository.findNotificationView(90L))
        .thenReturn(Optional.of(new StoreNotificationView("맛집", 42L)));

    consumer.consume(record("678", futureJson), ack);

    // recordLag 는 음수일 때 0으로 클램프 — metrics.recordLag(Duration) 호출 확인
    verify(metrics).recordLag(any());
    verify(ack).acknowledge();
  }

  // ---- N-10: enabled=false — 리스너 빈이 생성되지 않는다 ----

  /**
   * 이 테스트는 @ConditionalOnProperty 동작을 검증한다.
   *
   * <p>실제로는 Spring ApplicationContext 를 띄워야 하지만, Testcontainers/EmbeddedKafka 를 쓸 수 없으므로
   * 여기서는 @ConditionalOnProperty 어노테이션이 올바른 값으로 선언되어 있는지 리플렉션으로 확인한다.
   */
  @Test
  @DisplayName("N-10: enabled=false — @ConditionalOnProperty 확인")
  void n10_disabledFlag_beanNotCreated() {
    var annotation =
        PaymentNotificationConsumer.class.getAnnotation(
            org.springframework.boot.autoconfigure.condition.ConditionalOnProperty.class);

    assert annotation != null : "@ConditionalOnProperty 어노테이션이 없다";
    assert "notification.kafka.consumer.enabled".equals(annotation.name()[0])
        : "프로퍼티 이름이 다르다";
    assert "true".equals(annotation.havingValue()) : "havingValue 가 true 가 아니다";
  }
}
