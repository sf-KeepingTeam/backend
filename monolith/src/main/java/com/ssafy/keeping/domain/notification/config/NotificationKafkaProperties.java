package com.ssafy.keeping.domain.notification.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 알림 Kafka 컨슈머 설정 프로퍼티. */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "notification.kafka.consumer")
public class NotificationKafkaProperties {

  /** 컨슈머 활성화 여부. 기본 false — Kafka 클러스터가 없으면 꺼둔다. */
  private boolean enabled = false;
}
