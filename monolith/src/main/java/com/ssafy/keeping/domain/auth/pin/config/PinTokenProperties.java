package com.ssafy.keeping.domain.auth.pin.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PIN 토큰 관련 설정 프로퍼티.
 *
 * <p>prefix {@code payment.pin}. {@code @ConfigurationPropertiesScan} (KeepingApplication)으로
 * 자동 등록된다. {@code @Configuration}을 추가하지 마라 — 이중 등록(T-2 함정) 위험.
 *
 * <p>설정 키 구조:
 * <pre>
 * payment:
 *   pin:
 *     token-ttl-seconds: 60          # intent 토큰 TTL (기존)
 *     session-token:
 *       enabled: false               # 세션 토큰 발급 허용 (기본 꺼짐)
 *       ttl-seconds: 180
 *       max-uses: 5
 *       max-amount-per-use: 100000
 *       max-amount-total: 300000
 *     rate-limit:
 *       enabled: true                # 기본 켬 — 보안 기능이지 성능 기능이 아니다
 *       per-minute: 10               # 고객당 분당 PIN 검증 상한
 * </pre>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "payment.pin")
public class PinTokenProperties {

  /** intent 토큰 TTL(초). 기존 {@code @Value}에서 흡수. 기본값 60초. */
  private long tokenTtlSeconds = 60;

  private SessionToken sessionToken = new SessionToken();

  private RateLimit rateLimit = new RateLimit();

  @Getter
  @Setter
  public static class SessionToken {
    /** 세션 토큰 발급 허용 여부. 기본 꺼짐 — 인프라가 Redis 공유를 확인한 뒤 켠다. */
    private boolean enabled = false;

    /** 세션 토큰 TTL(초). */
    private long ttlSeconds = 180;

    /**
     * 토큰 1개로 허용되는 최대 결제 건수. qr-service가 {@code mu} 클레임으로 검증한다.
     * 서버 설정값으로만 채운다 — 클라이언트 요청으로 받지 않는다.
     */
    private int maxUses = 5;

    /**
     * 건당 최대 결제 금액(원). qr-service가 {@code amax} 클레임으로 검증한다.
     * 서버 설정값으로만 채운다 — 클라이언트 요청으로 받지 않는다.
     */
    private long maxAmountPerUse = 100_000L;

    /**
     * 세션 토큰당 누적 최대 결제 금액(원). qr-service가 {@code atot} 클레임으로 Redis INCRBY로 검증한다.
     * 서버 설정값으로만 채운다 — 클라이언트 요청으로 받지 않는다.
     */
    private long maxAmountTotal = 300_000L;
  }

  @Getter
  @Setter
  public static class RateLimit {

    /**
     * rate limit 활성 여부. 기본 켬 — 보안 기능이지 성능 기능이 아니다.
     * 비상 시에만 {@code false}로 끈다.
     */
    private boolean enabled = true;

    /**
     * 고객당 분당 PIN 검증 호출 상한.
     *
     * <p>기본 10 — 정상 사용자는 결제 1건당 1회(intent 토큰) 또는
     * 세션당 1회(세션 토큰, mu=5)를 호출한다. 분당 10회면 재시도·오조작을
     * 넉넉히 흡수하면서도 Argon2(8MB × 3회) 고갈 공격은 막는다.
     */
    private int perMinute = 10;
  }
}
