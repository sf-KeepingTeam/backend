package com.ssafy.keeping.domain.auth.pin.service;

import com.ssafy.keeping.domain.auth.pin.config.PinTokenProperties;
import com.ssafy.keeping.domain.auth.pin.model.CustomerPinAuth;
import com.ssafy.keeping.domain.auth.pin.repository.CustomerPinAuthRepository;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PinAuthService {

  private final CustomerPinAuthRepository customerPinAuthRepository;
  private final PasswordEncoder passwordEncoder;
  private final Clock clock;
  private final StringRedisTemplate stringRedisTemplate;
  private final PinTokenProperties pinTokenProperties;

  // === 정책 상수(필요시 yml -> @Value 로 교체 가능) ===
  private static final int MAX_FAILED_ATTEMPTS = 5; // 연속 실패 n회 시 잠금
  private static final int LOCK_MINUTES = 5; // 잠금 지속 시간(분)
  private static final int PIN_LENGTH = 6;

  /**
   * PIN 신규 설정 또는 재설정.
   *
   * <p>성공 시 {@code pin-token:revoke:{customerId}} 에 현재 epoch 초를 기록한다.
   * 이미 발급된 세션 토큰의 {@code iat}가 이 시각보다 이전이면 qr-service(#42)가 거부한다.
   *
   * <p><b>Redis 공유 전제</b>: monolith는 {@code SPRING_DATA_REDIS_HOST},
   * qr-service는 {@code REDIS_HOST}를 읽는다. 같은 인스턴스를 가리키는지 인프라가 확인해야 한다.
   * 확인 전까지 qr-service의 revocation check는 기본 꺼짐({@code revocation-check=false}).
   *
   * <p><b>Redis 장애 대응</b>: revocation 기록 실패는 PIN 변경을 롤백하지 않는다.
   * Redis 때문에 PIN 변경이 실패하는 게 더 나쁘다. try/catch로 감싸고 warn만 남긴다.
   */
  @Transactional
  public void setOrUpdatePin(Long customerId, String rawPin) {
    validatePinFormat(rawPin);

    String hash = passwordEncoder.encode(rawPin);
    LocalDateTime now = LocalDateTime.now(clock);

    CustomerPinAuth row = customerPinAuthRepository.findById(customerId).orElse(null);

    if (row == null) { // 초기 설정
      row =
          CustomerPinAuth.builder()
              .customerId(customerId)
              .pinHash(hash)
              .failedCount(0)
              .lockedUntil(null)
              .setAt(now)
              .updatedAt(now)
              .lastVerifyAt(null)
              .build();
    } else { // 재설정
      row.setPinHash(hash);
      row.setFailedCount(0);
      row.setLockedUntil(null);
      row.setSetAt(now);
      row.setUpdatedAt(now);
    }
    customerPinAuthRepository.save(row);

    // PIN 변경 시 기존 세션 토큰 폐기 기록 — Redis 장애가 PIN 변경을 막으면 안 된다
    recordPinRevocation(customerId);
  }

  /** 본인 확인 후 새 PIN으로 변경 */
  @Transactional
  public void changePin(Long customerId, String currentPin, String newPin) {
    boolean ok = verify(customerId, currentPin);
    if (!ok) {
      throw new CustomException(ErrorCode.PIN_INVALID); // 결제 비밀번호(PIN)가 올바르지 않습니다.
    }
    setOrUpdatePin(customerId, newPin);
  }

  /**
   * 승인 시 PIN 검증 - 잠금 중이면 PIN_LOCKED - 미설정이면 PIN_NOT_SET - 불일치면 실패 카운트 증가/임계 도달 시 잠금 → false - 일치면
   * 실패 카운트 초기화/성공시각 갱신(+필요시 재해시) → true
   */
  @Transactional
  public boolean verify(Long customerId, String rawPin) {
    if (rawPin == null || rawPin.isBlank()) {
      throw new CustomException(ErrorCode.PIN_REQUIRED); // 결제 비밀번호(PIN)는 필수입니다.
    }

    CustomerPinAuth row =
        customerPinAuthRepository
            .findById(customerId)
            .orElseThrow(
                () -> new CustomException(ErrorCode.PIN_NOT_SET)); // 설정된 결제 비밀번호(PIN)가 없습니다.

    LocalDateTime now = LocalDateTime.now(clock);

    // 잠금 체크
    if (row.getLockedUntil() != null && now.isBefore(row.getLockedUntil())) {
      throw new CustomException(ErrorCode.PIN_LOCKED); // PIN 입력이 일정 시간 잠겨 있습니다. 잠시 후 다시 시도하세요.
    }

    // 일치 여부 확인
    boolean matches = passwordEncoder.matches(rawPin, row.getPinHash());
    if (!matches) {
      int next = row.getFailedCount() + 1;
      row.setFailedCount(next);
      row.setUpdatedAt(now);

      if (next >= MAX_FAILED_ATTEMPTS) { // 잠금 횟수에 도달
        row.setLockedUntil(now.plusMinutes(LOCK_MINUTES));
        row.setFailedCount(0); // 잠금과 함께 카운터 초기화
      }
      customerPinAuthRepository.save(row);
      return false;
    }

    // 성공 처리: 이미 정상 상태면 불필요한 UPDATE 생략 (§4-6 option a)
    // last_verify_at 은 codebase 내 읽는 곳이 없으므로 갱신하지 않는다.
    if (row.getFailedCount() != 0 || row.getLockedUntil() != null) {
      row.setFailedCount(0);
      row.setLockedUntil(null);
      row.setUpdatedAt(now);
      customerPinAuthRepository.save(row);
    }
    return true;
  }

  /**
   * PIN 변경 시 발급된 세션 토큰을 무효화하기 위해 revocation 시각을 Redis에 기록한다.
   *
   * <p>키: {@code pin-token:revoke:{customerId}}, 값: 현재 epoch 초(문자열)
   * TTL: {@code session-token.ttl-seconds + 30초} — 이 시간이 지나면 토큰도 어차피 만료됨.
   *
   * <p><b>🔴 Redis 공유 전제</b>: monolith {@code SPRING_DATA_REDIS_HOST}와
   * qr-service {@code REDIS_HOST}가 같은 인스턴스인지 인프라가 확인해야 한다.
   * 확인 전까지 qr-service의 revocation check 플래그는 기본 꺼짐이므로 이 기록은 무시된다.
   * 인프라 확인 후 qr-service의 {@code payment.pin.session-token.revocation-check=true}로 켠다.
   *
   * <p><b>Redis 장애 시</b>: try/catch로 감싸고 warn만 남긴다. PIN 변경은 롤백하지 않는다.
   * PIN 변경이 Redis 때문에 실패하는 게 더 나쁘다 (§V2-8 비대칭 대응 원칙).
   */
  private void recordPinRevocation(Long customerId) {
    try {
      long nowEpochSec = Instant.now(clock).getEpochSecond();
      long ttlSec = pinTokenProperties.getSessionToken().getTtlSeconds() + 30;
      String key = "pin-token:revoke:" + customerId;
      stringRedisTemplate.opsForValue().set(key, String.valueOf(nowEpochSec), ttlSec, TimeUnit.SECONDS);
    } catch (Exception e) {
      log.warn("[PIN_TOKEN] revoke 기록 실패 customerId={} error={}", customerId, e.getMessage());
    }
  }

  private void validatePinFormat(String rawPin) {
    if (rawPin == null || rawPin.isBlank()) {
      throw new CustomException(ErrorCode.PIN_REQUIRED); // 결제 비밀번호(PIN)는 필수입니다.
    }
    if (rawPin.length() != PIN_LENGTH) {
      throw new CustomException(ErrorCode.PIN_LENGTH_INVALID);
    }
    for (int i = 0; i < rawPin.length(); i++) { // 숫자만 허용
      if (!Character.isDigit(rawPin.charAt(i))) {
        throw new CustomException(ErrorCode.BAD_REQUEST);
      }
    }
  }
}
