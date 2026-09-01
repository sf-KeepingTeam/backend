package com.ssafy.keeping.domain.notification.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ssafy.keeping.domain.notification.dto.PaymentNotificationEvent;
import com.ssafy.keeping.domain.notification.dto.StoreNotificationView;
import com.ssafy.keeping.domain.notification.entity.NotificationType;
import com.ssafy.keeping.domain.notification.entity.ProcessedEvent;
import com.ssafy.keeping.domain.notification.metrics.NotificationConsumerMetrics;
import com.ssafy.keeping.domain.notification.repository.ProcessedEventRepository;
import com.ssafy.keeping.domain.notification.service.NotificationService;
import com.ssafy.keeping.domain.store.repository.StoreRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * {@code keeping.payment.notification.v1} 토픽의 Kafka 컨슈머.
 *
 * <p>qr-service 가 발행한 결제 알림 이벤트를 소비하여, monolith 의 {@link NotificationService} 를 통해
 * SSE/FCM 알림을 발송한다.
 *
 * <p>순서: 기록(processed_event) → 발송. 기록 후 발송 전에 죽으면 알림이 안 가지만, 결제 완료 알림이 두 번
 * 오는 것(중복)보다 덜 나쁘다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "notification.kafka.consumer.enabled", havingValue = "true")
public class PaymentNotificationConsumer {

  private static final String TOPIC = "keeping.payment.notification.v1";
  private static final String DLT_TOPIC = "keeping.payment.notification.v1.dlt";

  private final NotificationService notificationService;
  private final StoreRepository storeRepository;
  private final ProcessedEventRepository processedEventRepository;
  private final NotificationConsumerMetrics metrics;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;

  public PaymentNotificationConsumer(
      NotificationService notificationService,
      StoreRepository storeRepository,
      ProcessedEventRepository processedEventRepository,
      NotificationConsumerMetrics metrics,
      @Qualifier("notificationKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate) {
    this.notificationService = notificationService;
    this.storeRepository = storeRepository;
    this.processedEventRepository = processedEventRepository;
    this.metrics = metrics;
    this.kafkaTemplate = kafkaTemplate;

    this.objectMapper = new ObjectMapper();
    this.objectMapper.registerModule(new JavaTimeModule());
    this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
  }

  @KafkaListener(
      topics = TOPIC,
      groupId = "keeping-notification-consumer",
      containerFactory = "notificationKafkaListenerContainerFactory")
  public void consume(ConsumerRecord<String, String> record, Acknowledgment ack) {
    // 1. 파싱
    PaymentNotificationEvent event;
    try {
      event = objectMapper.readValue(record.value(), PaymentNotificationEvent.class);
    } catch (JsonProcessingException e) {
      log.error(
          "[NOTI_CONSUME] dlt=PARSE_ERROR eventId=unknown error={}", e.getMessage());
      kafkaTemplate.send(DLT_TOPIC, record.key(), record.value());
      metrics.dlt();
      metrics.consumed("UNKNOWN", "PARSE_ERROR");
      ack.acknowledge();
      return;
    }

    String eventId = event.eventId();
    String eventType = event.eventType();

    // 2. 지연 측정
    recordLag(event.occurredAt(), eventId);

    // 3. 멱등성 — 삽입을 먼저 시도하고 유니크 제약 위반을 잡는다
    try {
      processedEventRepository.saveAndFlush(
          new ProcessedEvent(eventId, eventType, LocalDateTime.now()));
    } catch (DataIntegrityViolationException e) {
      log.warn("[NOTI_CONSUME] skip=DUPLICATE eventId={}", eventId);
      metrics.duplicateSkipped();
      metrics.consumed(eventType, "DUPLICATE");
      ack.acknowledge();
      return;
    }

    // 4. 매장 조회 (프로젝션 1쿼리)
    StoreNotificationView storeView =
        storeRepository.findNotificationView(event.storeId()).orElse(null);
    String storeName = storeView != null ? storeView.storeName() : "매장";
    Long ownerId = storeView != null ? storeView.ownerId() : null;

    // 5. 알림 발송 — 처리 실패 시 예외를 전파하여 재시도 대상으로 만든다
    switch (eventType) {
      case "PAYMENT_APPROVED" -> {
        // 점주 알림
        if (ownerId != null) {
          String ownerContent = String.format("%,d원 결제가 완료되었습니다.", event.amount());
          notificationService.sendToOwner(ownerId, NotificationType.PAYMENT_APPROVED, ownerContent);
        }
        // 고객 알림
        String customerContent =
            String.format("%s에서 %,d원 결제가 완료되었습니다.", storeName, event.amount());
        notificationService.sendToCustomer(
            event.customerId(), NotificationType.PAYMENT_APPROVED, customerContent);
      }
      case "PAYMENT_REQUESTED" -> {
        String content =
            String.format("%s에서 %,d원 결제 요청이 도착했습니다.", storeName, event.amount());
        notificationService.sendToCustomer(
            event.customerId(), NotificationType.PAYMENT_REQUEST, content);
      }
      default -> {
        log.warn(
            "[NOTI_CONSUME] dlt=UNKNOWN_EVENT_TYPE eventId={} eventType={}",
            eventId,
            eventType);
        kafkaTemplate.send(DLT_TOPIC, record.key(), record.value());
        metrics.dlt();
        metrics.consumed(eventType, "UNKNOWN_TYPE");
        ack.acknowledge();
        return;
      }
    }

    log.info(
        "[NOTI_CONSUME] eventId={} type={} intentId={} lagMs={}",
        eventId,
        eventType,
        event.intentId(),
        computeLagMs(event.occurredAt()));

    metrics.consumed(eventType, "SUCCESS");
    ack.acknowledge();
  }

  private void recordLag(OffsetDateTime occurredAt, String eventId) {
    if (occurredAt == null) {
      return;
    }
    Duration lag = Duration.between(occurredAt.toInstant(), java.time.Instant.now());
    if (lag.isNegative()) {
      log.warn(
          "[NOTI_CONSUME] negative_lag eventId={} lagMs={}", eventId, lag.toMillis());
    }
    metrics.recordLag(lag);
  }

  private long computeLagMs(OffsetDateTime occurredAt) {
    if (occurredAt == null) {
      return -1;
    }
    long ms = Duration.between(occurredAt.toInstant(), java.time.Instant.now()).toMillis();
    return Math.max(ms, 0);
  }
}
