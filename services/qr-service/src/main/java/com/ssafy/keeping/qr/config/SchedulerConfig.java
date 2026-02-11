package com.ssafy.keeping.qr.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄러 활성화 설정
 * - OutboxProcessor: 30초마다 대기 중인 Saga 이벤트 처리
 * - RecoveryJob: 5분마다 만료된 PaymentIntent 및 오래된 이벤트 처리
 */
@Configuration
@EnableScheduling
public class SchedulerConfig {
}
