package com.ssafy.keeping.global.outbox.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.global.outbox.model.OutboxEvent;
import com.ssafy.keeping.global.outbox.model.OutboxStatus;
import com.ssafy.keeping.global.outbox.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Outbox 이벤트 발행 서비스
 *
 * 사용 예시:
 * <pre>
 * @Transactional
 * public Customer createCustomer(...) {
 *     Customer customer = customerRepository.save(...);
 *     outboxPublisher.publish("Customer", customer.getCustomerId().toString(),
 *         "CustomerCreated", new CustomerCreatedPayload(customer.getCustomerId(), ...));
 *     return customer;
 * }
 * </pre>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * 이벤트 발행 (현재 트랜잭션에 참여)
     *
     * @param aggregateType 집합체 유형 (예: "Customer", "Group")
     * @param aggregateId   집합체 ID
     * @param eventType     이벤트 유형 (예: "CustomerCreated", "GroupDisbandInitiated")
     * @param payload       이벤트 페이로드 객체 (JSON으로 직렬화됨)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(String aggregateType, String aggregateId, String eventType, Object payload) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);

            OutboxEvent event = OutboxEvent.builder()
                    .aggregateType(aggregateType)
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .payload(payloadJson)
                    .status(OutboxStatus.PENDING)
                    .build();

            outboxEventRepository.save(event);

            log.debug("[Outbox] 이벤트 저장 - type: {}, aggregateType: {}, aggregateId: {}",
                    eventType, aggregateType, aggregateId);

        } catch (JsonProcessingException e) {
            log.error("[Outbox] 페이로드 직렬화 실패 - eventType: {}", eventType, e);
            throw new RuntimeException("Failed to serialize outbox event payload", e);
        }
    }

    /**
     * 이벤트 발행 (새 트랜잭션으로)
     * 주의: 호출자의 트랜잭션과 별도로 커밋됨
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publishInNewTransaction(String aggregateType, String aggregateId, String eventType, Object payload) {
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);

            OutboxEvent event = OutboxEvent.builder()
                    .aggregateType(aggregateType)
                    .aggregateId(aggregateId)
                    .eventType(eventType)
                    .payload(payloadJson)
                    .status(OutboxStatus.PENDING)
                    .build();

            outboxEventRepository.save(event);

            log.debug("[Outbox] 이벤트 저장 (새 트랜잭션) - type: {}, aggregateType: {}, aggregateId: {}",
                    eventType, aggregateType, aggregateId);

        } catch (JsonProcessingException e) {
            log.error("[Outbox] 페이로드 직렬화 실패 - eventType: {}", eventType, e);
            throw new RuntimeException("Failed to serialize outbox event payload", e);
        }
    }

    /**
     * 이벤트 발행 (JSON 문자열로 직접 저장)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishRaw(String aggregateType, String aggregateId, String eventType, String payloadJson) {
        OutboxEvent event = OutboxEvent.builder()
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payload(payloadJson)
                .status(OutboxStatus.PENDING)
                .build();

        outboxEventRepository.save(event);

        log.debug("[Outbox] 이벤트 저장 (raw) - type: {}, aggregateType: {}, aggregateId: {}",
                eventType, aggregateType, aggregateId);
    }
}
