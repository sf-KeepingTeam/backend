package com.ssafy.keeping.global.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * RestTemplate 설정
 * - RestTemplateBuilder를 사용하여 Micrometer Tracing 자동 설정 적용
 * - TraceId가 HTTP 헤더로 자동 전파됨
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofMillis(5000))  // 5초
                .setReadTimeout(Duration.ofMillis(10000))    // 10초
                .build();
    }
}