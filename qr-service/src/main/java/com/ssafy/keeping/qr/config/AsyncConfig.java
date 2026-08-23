package com.ssafy.keeping.qr.config;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 비동기 처리 및 스케줄링 설정 - @EnableAsync: 비동기 메서드 활성화 - @EnableScheduling: @Scheduled 메서드 활성화
 * (PaymentRecoveryService용)
 */
@Slf4j
@Configuration
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(PaymentTuningProperties.class)
public class AsyncConfig {

    /**
     * 알림 전용 스레드풀.
     *
     * <p>큐 포화 시 작업을 버리고(DiscardPolicy + 경고 로그) 결제 스레드를 블로킹하지 않는다.
     * CallerRunsPolicy 를 쓰면 결제 스레드가 알림을 대신 처리하게 되어 개악이므로 금지.
     */
    @Bean("notificationExecutor")
    public Executor notificationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(64);
        executor.setThreadNamePrefix("notif-");
        executor.setRejectedExecutionHandler((runnable, pool) -> {
            log.warn("[NOTIFICATION_DROPPED] 알림 스레드풀 큐 포화 — 작업 버림. "
                    + "poolSize={} activeCount={} queueSize={}",
                    pool.getPoolSize(), pool.getActiveCount(),
                    pool.getQueue().size());
        });
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor;
    }
}
