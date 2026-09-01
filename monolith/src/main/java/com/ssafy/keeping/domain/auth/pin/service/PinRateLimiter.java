package com.ssafy.keeping.domain.auth.pin.service;

import com.ssafy.keeping.domain.auth.pin.config.PinTokenProperties;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * PIN 검증 엔드포인트에 대한 고객별 분당 호출 횟수를 제한한다.
 *
 * <h3>왜 이 클래스가 필요한가</h3>
 * <p>PIN 검증은 Argon2(메모리 8MB × 반복 3회) 해시 비교를 수행한다. 올바른 PIN을 알고 있는 공격자가
 * 이 엔드포인트만 반복 호출하면 CPU를 독점해 결제 전체가 마비된다. 세션 토큰(#41)이 켜지면
 * 정상 사용자의 호출 빈도가 1/5로 줄어들므로 상한을 낮게 잡아도 정상 사용자에게 영향이 없다.
 *
 * <h3>Redis 키</h3>
 * <pre>
 * 키:  pin-token:rate:{customerId}:{epochMinute}
 * 값:  카운트(INCR)
 * TTL: 60초 (INCR 결과가 1일 때만 EXPIRE — 갱신하면 창이 무한히 밀린다)
 * </pre>
 *
 * <h3>고정 창(fixed window) 선택 이유</h3>
 * <p>슬라이딩 윈도우는 복잡도만 늘고, 여기서 막으려는 것은 정밀한 요금 제어가 아니라
 * <b>CPU 고갈</b>이다. 분 단위 고정 창으로 충분하다.
 *
 * <h3>🔴 Redis 장애 시 통과 — 의도적 비대칭 설계</h3>
 * <p>이 클래스의 Redis는 <b>속도 제한</b>용이다. 장애가 나도 공격자는 여전히 올바른 PIN을 알아야
 * Argon2가 실행된다. 반면 Redis 장애로 정상 고객의 결제가 전부 막히는 것이 더 나쁘다.
 *
 * <p>qr-service의 {@code PinTokenVerifier}(#42)와 다른 이유: 그쪽 Redis는 토큰 재사용을
 * 막는 방어선이다. 뚫리면 금전 피해가 생기므로 거부한다. 여기는 그 상황이 아니다.
 * <b>같은 원칙("실패했을 때 무엇을 잃는가")에서 나온 반대 결론</b>이다. 맞추지 마라.
 *
 * @see PinTokenService
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PinRateLimiter {

  private static final String KEY_PREFIX = "pin-token:rate:";

  private final StringRedisTemplate stringRedisTemplate;
  private final PinTokenProperties properties;
  private final Clock clock;

  /**
   * 호출 횟수를 증가시키고, 분당 상한 초과 시 {@link ErrorCode#PIN_TOKEN_RATE_LIMITED} 예외를 던진다.
   *
   * <p>{@code enabled=false}이면 Redis를 전혀 호출하지 않고 즉시 반환한다.
   *
   * @param customerId 고객 ID (SecurityContext에서 추출된 값)
   * @throws CustomException PIN_TOKEN_RATE_LIMITED (429) — 분당 상한 초과
   */
  public void checkAndIncrement(Long customerId) {
    PinTokenProperties.RateLimit rl = properties.getRateLimit();
    if (!rl.isEnabled()) {
      return;
    }

    try {
      long epochMinute = Instant.now(clock).getEpochSecond() / 60;
      String key = KEY_PREFIX + customerId + ":" + epochMinute;

      Long count = stringRedisTemplate.opsForValue().increment(key);
      if (count == null) {
        // increment() 가 null 을 반환하는 경우는 실질적으로 없지만 방어 코드
        return;
      }

      // INCR 결과가 1 이면 새 창 — TTL 설정. 갱신하면 창이 무한히 밀린다(R-3)
      if (count == 1L) {
        stringRedisTemplate.expire(key, 60, TimeUnit.SECONDS);
      }

      if (count > rl.getPerMinute()) {
        log.warn(
            "[PIN_RATE_LIMIT] customerId={} count={} limit={} window={}",
            customerId,
            count,
            rl.getPerMinute(),
            epochMinute);
        throw new CustomException(ErrorCode.PIN_TOKEN_RATE_LIMITED);
      }

    } catch (CustomException e) {
      // 비즈니스 예외는 그대로 전파
      throw e;
    } catch (Exception e) {
      // 🔴 Redis 장애 시 통과 — 속도 제한 Redis가 뚫려도 PIN을 알아야 Argon2가 돈다.
      // Redis 장애로 정상 고객 결제가 전부 막히는 것이 더 나쁘다 (§V2-8 비대칭 대응).
      log.warn(
          "[PIN_RATE_LIMIT] Redis 장애 — rate limit 건너뜀 customerId={} error={}",
          customerId,
          e.getMessage());
    }
  }
}
