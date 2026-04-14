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
     * - PULL: Cache-Aside (캐시 미스 시 조회 후 저장)
     * - PUSH: Webhook Push + Cache-Aside Fallback (현재 구현)
     */
    public enum Mode {
        NONE,  // 캐시 미사용, 항상 모놀리스 호출
        PULL,  // Cache-Aside (캐시 미스 시 조회 후 저장)
        PUSH   // Webhook Push + Cache-Aside Fallback (현재 구현)
    }

    private Mode mode = Mode.PUSH;

    @PostConstruct
    public void logCacheMode() {
        log.info("========================================");
        log.info("캐시 모드: {}", mode);
        log.info("========================================");
    }

    /**
     * 캐시 사용 여부 (PULL 또는 PUSH 모드)
     */
    public boolean isCacheEnabled() {
        return mode != Mode.NONE;
    }

    /**
     * PUSH 모드 여부 (Webhook Push + Cache Warming)
     */
    public boolean isPushEnabled() {
        return mode == Mode.PUSH;
    }
}
