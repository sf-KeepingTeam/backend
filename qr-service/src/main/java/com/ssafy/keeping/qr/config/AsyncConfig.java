package com.ssafy.keeping.qr.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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

    // ── 알림 스레드풀 통제변인 (2026-08-31 신설) ──────────────────────────
    //  기본값은 기존과 동일(2/4/64) — 아무것도 안 주면 F6r 조건이 그대로 재현된다.
    //  근거: F6r 러닝에서 executor_queued_tasks=64(정원 전체) · active_threads=4(최대) 로
    //        큐 포화가 실측됐고, HTTP 호출 실패·재시도·서킷 OPEN 은 전부 0이었다.
    //        → 알림 유실 41.3% 의 원인은 이 세 값이다.
    //  ⚠️ core 와 max 를 함께 올려야 의미가 있다. ThreadPoolExecutor 는
    //     core 를 넘으면 큐부터 채우고, 큐가 꽉 차야 max 까지 확장한다.
    @Value("${notification.executor.core-size:2}")
    private int notifCoreSize;

    @Value("${notification.executor.max-size:4}")
    private int notifMaxSize;

    @Value("${notification.executor.queue-capacity:64}")
    private int notifQueueCapacity;

    /**
     * 알림 전용 스레드풀.
     *
     * <p>큐 포화 시 작업을 버리고(DiscardPolicy + 경고 로그) 결제 스레드를 블로킹하지 않는다.
     * CallerRunsPolicy 를 쓰면 결제 스레드가 알림을 대신 처리하게 되어 개악이므로 금지.
     */
    @Bean("notificationExecutor")
    public Executor notificationExecutor(MeterRegistry meterRegistry) {
        Counter droppedCounter = meterRegistry.counter("notification_dropped_total");
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(notifCoreSize);
        executor.setMaxPoolSize(notifMaxSize);
        executor.setQueueCapacity(notifQueueCapacity);
        executor.setThreadNamePrefix("notif-");
        executor.setRejectedExecutionHandler((runnable, pool) -> {
            droppedCounter.increment();
            log.warn("[NOTIFICATION_DROPPED] 알림 스레드풀 큐 포화 — 작업 버림. "
                    + "poolSize={} activeCount={} queueSize={}",
                    pool.getPoolSize(), pool.getActiveCount(),
                    pool.getQueue().size());
        });
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        log.info("[NOTIF_EXECUTOR] core={} max={} queue={}",
                notifCoreSize, notifMaxSize, notifQueueCapacity);
        return executor;
    }

    /**
     * 롱폴링 대기자 폴러 전용 스케줄러.
     *
     * <p>기존 {@code @Scheduled} 스레드(Spring 기본 1개, 복구 스케줄러 점유)와 분리.
     * core 2 — 한 스레드가 느린 MGET 을 처리하는 동안 다른 스레드가 다음 사이클을 받는다.
     */
    @Bean("intentWaitScheduler")
    public ScheduledExecutorService intentWaitScheduler() {
        return Executors.newScheduledThreadPool(2,
                r -> {
                    Thread t = new Thread(r, "intent-wait-" + r.hashCode());
                    t.setDaemon(true);
                    return t;
                });
    }
}
