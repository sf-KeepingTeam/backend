package com.ssafy.keeping.domain.payment.toss.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 토스페이먼츠 설정
 * application.yml의 payment.toss 설정을 바인딩
 */
@Configuration
@ConfigurationProperties(prefix = "payment.toss")
@Getter
@Setter
public class TossPaymentConfig {

    /**
     * 토스페이먼츠 시크릿 키
     * - 테스트: test_sk_... 로 시작
     * - 운영: live_sk_... 로 시작
     */
    private String secretKey;

    /**
     * 토스페이먼츠 API 기본 URL
     */
    private String baseUrl = "https://api.tosspayments.com";

    /**
     * 타임아웃 설정 (밀리초)
     */
    private int connectTimeout = 5000;
    private int readTimeout = 10000;
}
