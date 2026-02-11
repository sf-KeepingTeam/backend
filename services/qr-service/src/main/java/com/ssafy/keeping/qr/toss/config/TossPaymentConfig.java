package com.ssafy.keeping.qr.toss.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "payment.toss")
@Getter
@Setter
public class TossPaymentConfig {

    private String secretKey;
    private String baseUrl = "https://api.tosspayments.com";
    private int connectTimeout = 5000;
    private int readTimeout = 10000;
}
