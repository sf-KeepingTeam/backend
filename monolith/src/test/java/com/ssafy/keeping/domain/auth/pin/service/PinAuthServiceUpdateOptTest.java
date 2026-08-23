package com.ssafy.keeping.domain.auth.pin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ssafy.keeping.domain.auth.pin.model.CustomerPinAuth;
import com.ssafy.keeping.domain.auth.pin.repository.CustomerPinAuthRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * PinAuthService §4-6 최적화 검증 — 성공 시 failedCount가 이미 0이면 UPDATE 쿼리를 발생시키지 않는다.
 *
 * <p>실행 금지 — 작성만 (세트 #32 제약).
 */
@ExtendWith(MockitoExtension.class)
class PinAuthServiceUpdateOptTest {

  @Mock private CustomerPinAuthRepository customerPinAuthRepository;
  @Mock private PasswordEncoder passwordEncoder;
  @Mock private Clock clock;

  @InjectMocks private PinAuthService pinAuthService;

  private static final Long CUSTOMER_ID = 42L;
  private static final String RAW_PIN = "123456";
  private static final String PIN_HASH = "$argon2id$hashed";

  // === P-7: 성공·무변화일 때 UPDATE 쿼리가 발생하지 않음 ===

  @Test
  @DisplayName("P-7: PIN 성공 시 failedCount=0, lockedUntil=null이면 save()를 호출하지 않는다")
  void verify_success_no_update_when_already_clean() {
    Instant now = Instant.parse("2026-08-23T10:00:00Z");
    when(clock.instant()).thenReturn(now);
    when(clock.getZone()).thenReturn(ZoneId.of("UTC"));

    CustomerPinAuth row =
        CustomerPinAuth.builder()
            .customerId(CUSTOMER_ID)
            .pinHash(PIN_HASH)
            .failedCount(0)          // 이미 정상
            .lockedUntil(null)       // 잠금 없음
            .setAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();

    when(customerPinAuthRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(row));
    when(passwordEncoder.matches(RAW_PIN, PIN_HASH)).thenReturn(true);

    boolean result = pinAuthService.verify(CUSTOMER_ID, RAW_PIN);

    assertThat(result).isTrue();
    // save()가 호출되지 않아야 한다 — 불필요한 UPDATE 제거 확인
    verify(customerPinAuthRepository, never()).save(any());
  }

  @Test
  @DisplayName("P-7: PIN 성공 시 failedCount>0이면 save()를 호출하여 초기화한다")
  void verify_success_updates_when_failed_count_nonzero() {
    Instant now = Instant.parse("2026-08-23T10:00:00Z");
    when(clock.instant()).thenReturn(now);
    when(clock.getZone()).thenReturn(ZoneId.of("UTC"));

    CustomerPinAuth row =
        CustomerPinAuth.builder()
            .customerId(CUSTOMER_ID)
            .pinHash(PIN_HASH)
            .failedCount(3)          // 이전 실패가 있었음
            .lockedUntil(null)
            .setAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();

    when(customerPinAuthRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(row));
    when(passwordEncoder.matches(RAW_PIN, PIN_HASH)).thenReturn(true);

    boolean result = pinAuthService.verify(CUSTOMER_ID, RAW_PIN);

    assertThat(result).isTrue();
    // failedCount를 초기화해야 하므로 save() 호출
    verify(customerPinAuthRepository).save(row);
    assertThat(row.getFailedCount()).isZero();
  }

  @Test
  @DisplayName("P-7: PIN 성공 시 lockedUntil이 과거값(잠금 해제됨)이면 save()를 호출하여 null로 정리한다")
  void verify_success_updates_when_locked_until_is_past() {
    Instant now = Instant.parse("2026-08-23T10:00:00Z");
    when(clock.instant()).thenReturn(now);
    when(clock.getZone()).thenReturn(ZoneId.of("UTC"));

    CustomerPinAuth row =
        CustomerPinAuth.builder()
            .customerId(CUSTOMER_ID)
            .pinHash(PIN_HASH)
            .failedCount(0)
            .lockedUntil(LocalDateTime.of(2026, 8, 23, 9, 50))  // 과거 — 잠금 해제됨
            .setAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();

    when(customerPinAuthRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(row));
    when(passwordEncoder.matches(RAW_PIN, PIN_HASH)).thenReturn(true);

    boolean result = pinAuthService.verify(CUSTOMER_ID, RAW_PIN);

    assertThat(result).isTrue();
    // lockedUntil이 non-null이므로 정리를 위해 save() 호출
    verify(customerPinAuthRepository).save(row);
    assertThat(row.getLockedUntil()).isNull();
  }
}
