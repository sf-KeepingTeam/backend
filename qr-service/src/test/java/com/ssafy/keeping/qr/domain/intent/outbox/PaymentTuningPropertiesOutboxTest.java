package com.ssafy.keeping.qr.domain.intent.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssafy.keeping.qr.config.PaymentTuningProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O-6: PaymentTuningProperties 바인딩 테스트.
 *
 * <p>Spring 컨텍스트를 띄우지 않고 자바 필드 기본값을 직접 검증한다.
 * {@code @SpringBootTest + @EnableConfigurationProperties} 이중 등록은
 * v2 에서 {@code NoUniqueBeanDefinitionException} 을 냈으므로 쓰지 않는다.
 */
class PaymentTuningPropertiesOutboxTest {

  @Test
  @DisplayName("O-6: transport 기본값 http, outbox.enabled 기본값 false")
  void defaultValues() {
    PaymentTuningProperties properties = new PaymentTuningProperties();

    assertThat(properties.getNotification().getTransport()).isEqualTo("http");
    assertThat(properties.getOutbox().isEnabled()).isFalse();
    assertThat(properties.getOutbox().getPollIntervalMs()).isEqualTo(500L);
    assertThat(properties.getOutbox().getBatchSize()).isEqualTo(100);
    assertThat(properties.getOutbox().getMaxRetry()).isEqualTo(10);
    assertThat(properties.getOutbox().getRetentionDays()).isEqualTo(7);
  }
}
