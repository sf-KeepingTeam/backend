package com.ssafy.keeping.domain.notification.config;

import java.util.HashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * 알림 Kafka 컨슈머 설정.
 *
 * <p>{@code notification.kafka.consumer.enabled=true} 일 때만 빈이 생성된다. 플래그가 꺼져 있으면
 * KafkaListener 컨테이너 팩토리 자체가 없으므로 Kafka 연결 시도가 발생하지 않는다.
 */
@Configuration
@ConditionalOnProperty(name = "notification.kafka.consumer.enabled", havingValue = "true")
// ⚠️ @EnableKafka 를 빼면 안 된다 (2026-08-24 인프라 수정 — result.md 결함 13)
//   application.yml 이 KafkaAutoConfiguration 을 exclude 하고 있다.
//   @KafkaListener 를 실제로 컨테이너로 만들어 주는 것은
//   KafkaListenerAnnotationBeanPostProcessor 이고, 그것을 등록하는 주체가 @EnableKafka 다.
//   보통은 KafkaAutoConfiguration 이 대신 넣어 주는데 그것을 제외해 놓았으므로
//   여기서 직접 붙여야 한다.
//   없으면 아래 빈들은 정상 생성되지만 리스너 컨테이너가 만들어지지 않아
//   **에러도 로그도 없이** 컨슈머가 영원히 돌지 않는다. 메시지는 토픽에 쌓이기만 한다.
//   이 클래스가 @ConditionalOnProperty 안에 있으므로 플래그가 꺼지면 @EnableKafka 도 적용되지 않는다.
@EnableKafka
public class NotificationKafkaConfig {

  @Value("${spring.kafka.bootstrap-servers}")
  private String bootstrapServers;

  @Bean
  public ConsumerFactory<String, String> notificationConsumerFactory() {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "keeping-notification-consumer");
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    return new DefaultKafkaConsumerFactory<>(props);
  }

  @Bean
  public ProducerFactory<String, String> notificationProducerFactory() {
    Map<String, Object> props = new HashMap<>();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    return new DefaultKafkaProducerFactory<>(props);
  }

  @Bean
  public KafkaTemplate<String, String> notificationKafkaTemplate() {
    return new KafkaTemplate<>(notificationProducerFactory());
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, String>
      notificationKafkaListenerContainerFactory() {
    ConcurrentKafkaListenerContainerFactory<String, String> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(notificationConsumerFactory());
    factory.setConcurrency(3);
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

    // DLT + 재시도: 1초 간격, 최대 2회 재시도 후 DLT 발행
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(notificationKafkaTemplate());
    CommonErrorHandler errorHandler =
        new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2));
    factory.setCommonErrorHandler(errorHandler);

    return factory;
  }
}
