package com.ssafy.keeping.qr.config;

import com.ssafy.keeping.qr.domain.intent.constant.PaymentStatus;
import com.ssafy.keeping.qr.domain.intent.repository.PaymentIntentRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

@Configuration
@RequiredArgsConstructor
public class MetricsConfig {

    private final PaymentIntentRepository paymentIntentRepository;
    private final Clock clock;

    @Bean
    public Gauge paymentUncertainCountGauge(MeterRegistry registry) {
        return Gauge.builder("payment_uncertain_count",
                        paymentIntentRepository,
                        repo -> repo.countByStatus(PaymentStatus.UNCERTAIN))
                .description("현재 UNCERTAIN 상태 PaymentIntent 개수")
                .register(registry);
    }

    @Bean
    public Gauge paymentUncertainOldestAgeGauge(MeterRegistry registry) {
        return Gauge.builder("payment_uncertain_oldest_age_seconds",
                        paymentIntentRepository,
                        repo -> repo.findOldestCreatedAtByStatus(PaymentStatus.UNCERTAIN)
                                .map(oldest -> Duration.between(oldest, LocalDateTime.now(clock)).getSeconds())
                                .orElse(0L))
                .description("가장 오래된 UNCERTAIN Intent가 생성된 이후 경과 시간(초)")
                .register(registry);
    }
}
