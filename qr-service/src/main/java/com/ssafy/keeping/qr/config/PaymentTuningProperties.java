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

    @Getter
    @Setter
    public static class Notification {
        /** true 면 알림을 AFTER_COMMIT + @Async 로 전송, false 면 기존 동기 경로 */
        private boolean async = true;
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
        /** 세트 #33이 추가할 필드 자리 */
        private boolean tokenEnabled = true;
        private int tokenTtlSeconds = 60;
    }
}
