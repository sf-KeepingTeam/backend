package com.ssafy.keeping.qr.config;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * 캐시 모드 설정 클래스
 * 부하 테스트에서 캐시 전략별 성능 비교를 위해 사용
 */
@Slf4j
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "cache")
public class CacheModeConfig {

    /**
     * 캐시 모드
     * - NONE: 캐시 미사용, 항상 모놀리스 호출
     * - CACHE_ASIDE: 캐시 미스 시 모놀리스에서 조회 후 저장 (Lazy Loading)
     * - WRITE_THROUGH: 서버 시작 시 전량 Cache Warming + 변경 시 Webhook으로 즉시 갱신 (현재 운영 모드)
     */
    public enum Mode {
        NONE,          // 캐시 미사용, 항상 모놀리스 호출
        CACHE_ASIDE,   // 캐시 미스 시 모놀리스 조회 후 저장 (Lazy Loading)
        WRITE_THROUGH  // Cache Warming + Webhook 기반 즉시 갱신
    }

    private Mode mode = Mode.WRITE_THROUGH;

    @PostConstruct
    public void logCacheMode() {
        log.info("========================================");
        log.info("캐시 모드: {}", mode);
        log.info("========================================");
    }

    /**
     * 캐시 사용 여부 (CACHE_ASIDE 또는 WRITE_THROUGH 모드)
     */
    public boolean isCacheEnabled() {
        return mode != Mode.NONE;
    }

    /**
     * WRITE_THROUGH 모드 여부 (Cache Warming + Webhook 즉시 갱신)
     */
    public boolean isWriteThroughEnabled() {
        return mode == Mode.WRITE_THROUGH;
    }
}
