package com.ssafy.keeping.qr.domain.intent.outbox;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * 아웃박스 전용 Kafka 프로듀서 설정.
 *
 * <p>{@code QrServiceApplication}이 {@code KafkaAutoConfiguration}을 exclude 했으므로
 * {@code KafkaTemplate} 빈을 직접 생성한다. {@code payment.outbox.enabled=false}(기본)이면
 * 이 설정 클래스 자체가 로드되지 않아 Kafka 관련 빈이 0개다.
 *
 * <p>{@code @EnableConfigurationProperties(KafkaProperties.class)}로
 * {@code spring.kafka.bootstrap-servers} 바인딩을 수동으로 활성화한다.
 */
@Configuration
@ConditionalOnProperty(name = "payment.outbox.enabled", havingValue = "true")
@EnableConfigurationProperties(KafkaProperties.class)
public class OutboxKafkaProducerConfig {

    private final KafkaProperties kafkaProperties;

    public OutboxKafkaProducerConfig(KafkaProperties kafkaProperties) {
        this.kafkaProperties = kafkaProperties;
    }

    @Bean
    public ProducerFactory<String, String> outboxProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaProperties.getBootstrapServers());

        // 키·값 모두 String — payload 는 이미 JSON 문자열
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        // acks=all: 브로커 1대에선 acks=1과 동일하지만 스케일 아웃 시 의미가 생긴다
        props.put(ProducerConfig.ACKS_CONFIG, "all");

        // 프로듀서 멱등성 — 재시도 중 중복 메시지를 줄인다
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        // 타임아웃: 기본 delivery.timeout(2분)은 폴러가 오래 막히므로 5초로 줄인다
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 5_000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 3_000);

        // retries: 기본값 유지 (Integer.MAX_VALUE with idempotence)
        // linger.ms: 기본 0 — 아웃박스 폴러는 건별 동기 발행이라 배치 모을 이유 없음

        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> outboxKafkaTemplate(
            ProducerFactory<String, String> outboxProducerFactory) {
        return new KafkaTemplate<>(outboxProducerFactory);
    }
}
