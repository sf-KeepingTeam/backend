package com.ssafy.keeping.global.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Firebase 초기화 (Stub)
 *
 * Firebase Admin SDK 의존성 제거 — 초기화 없이 로그만 남긴다.
 * FCM 발송은 FcmService에서 stub 처리.
 */
@Component
@Slf4j
public class FirebaseConfig {

    @PostConstruct
    public void initialize() {
        log.info("[FirebaseStub] Firebase 초기화 생략 (stub 모드)");
    }
}
