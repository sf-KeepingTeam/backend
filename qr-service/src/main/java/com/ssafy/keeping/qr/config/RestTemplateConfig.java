package com.ssafy.keeping.qr.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class RestTemplateConfig {

    @Value("${rest-template.connect-timeout:3000}")
    private int connectTimeout;

    @Value("${rest-template.read-timeout:5000}")
    private int readTimeout;

    @Value("${rest-template-write.connect-timeout:2000}")
    private int writeConnectTimeout;

    @Value("${rest-template-write.read-timeout:3000}")
    private int writeReadTimeout;

    @Value("${rest-template-recovery.connect-timeout:5000}")
    private int recoveryConnectTimeout;

    @Value("${rest-template-recovery.read-timeout:10000}")
    private int recoveryReadTimeout;

    /**
     * 기본 RestTemplate - 읽기 작업용 (5초 타임아웃)
     */
    @Bean
    @Primary
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofMillis(connectTimeout))
                .setReadTimeout(Duration.ofMillis(readTimeout))
                .build();
    }

    /**
     * 쓰기 작업용 RestTemplate - Fail-Fast (3초 타임아웃)
     * 결제 캡처 등 쓰기 작업에 사용
     */
    @Bean("writeRestTemplate")
    public RestTemplate writeRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofMillis(writeConnectTimeout))
                .setReadTimeout(Duration.ofMillis(writeReadTimeout))
                .build();
    }

    /**
     * 복구 작업용 RestTemplate - 여유있는 타임아웃 (10초)
     * 백그라운드 복구 작업에서 사용, 트랜잭션 외부에서 호출
     */
    @Bean("recoveryRestTemplate")
    public RestTemplate recoveryRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofMillis(recoveryConnectTimeout))
                .setReadTimeout(Duration.ofMillis(recoveryReadTimeout))
                .build();
    }
}
