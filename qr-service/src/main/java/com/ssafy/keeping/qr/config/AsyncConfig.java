package com.ssafy.keeping.qr.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 비동기 처리 및 스케줄링 설정
 * - @EnableAsync: 비동기 메서드 활성화
 * - @EnableScheduling: @Scheduled 메서드 활성화 (PaymentRecoveryService용)
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {
}
