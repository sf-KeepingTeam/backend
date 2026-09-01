package com.ssafy.keeping.qr.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 결제 관련 튜닝 설정.
 *
 * <p>세트 #30이 생성하고, #31·#33이 필드를 추가한다.
 * {@code @Value} 를 흩뿌리지 않고 이 클래스 하나에 묶는다.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "payment")
public class PaymentTuningProperties {

    private Notification notification = new Notification();
    private Approve approve = new Approve();
    private Pin pin = new Pin();
    private Outbox outbox = new Outbox();

    @Getter
    @Setter
    public static class Notification {
        /** true 면 알림을 AFTER_COMMIT + @Async 로 전송, false 면 기존 동기 경로 */
        private boolean async = true;

        /**
         * 알림 전송 수단. {@code "http"} 이면 기존 REST 경로, {@code "kafka"} 이면 아웃박스 → Kafka.
         *
         * <p>String 으로 둔 이유: enum 바인딩 실패 시 앱 기동이 깨지는데, 알림 경로 오타 하나로
         * 서비스 전체가 죽는 것은 과한 대가다. 판정은 쓰는 쪽에서 {@code "kafka".equals(transport)}
         * 로 한다.
         */
        private String transport = "http";
    }

    @Getter
    @Setter
    public static class Outbox {
        private boolean enabled = false;
        private long pollIntervalMs = 500;
        private int batchSize = 100;
        private int maxRetry = 10;
        private int retentionDays = 7;
    }

    @Getter
    @Setter
    public static class Approve {
        /** 세트 #31이 추가할 필드 자리 */
        private boolean splitTransaction = true;
    }

    @Getter
    @Setter
    public static class Pin {
        /**
         * PIN 토큰 로컬 검증 사용 여부.
         *
         * <p>기본값 false — v3 재측정(result.md §7-4 ③)에서 현 설계의 토큰이
         * Argon2 호출 횟수를 줄이지 못하고 순손실(무부하 수용량 −5.6%)로 확인됐다.
         * 세션 토큰 재설계(세트 #41·#42) 후 재측정으로 이득이 확인되면 켠다.
         */
        private boolean tokenEnabled = false;
        private int tokenTtlSeconds = 60;
        private SessionToken sessionToken = new SessionToken();

        @Getter
        @Setter
        public static class SessionToken {
            /**
             * 폐기 시각 확인 여부 (Redis {@code pin-token:revoke:{customerId}} 키 조회).
             *
             * <p>기본값 false — monolith 와 qr-service 가 같은 Redis 인스턴스를 바라보는지
             * 인프라가 확인하기 전까지는 꺼둔다. 공유가 확인되면 true 로 켠다.
             * 꺼져 있으면 폐기 경로가 작동하지 않으며, 토큰 TTL(180초)이 유일한 방어선이다.
             */
            private boolean revocationCheck = false;
        }
    }
}
