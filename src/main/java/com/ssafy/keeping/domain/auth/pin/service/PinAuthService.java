package com.ssafy.keeping.domain.auth.pin.service;

import com.ssafy.keeping.domain.auth.pin.model.CustomerPinAuth;
import com.ssafy.keeping.domain.auth.pin.repository.CustomerPinAuthRepository;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class PinAuthService {

    private final CustomerPinAuthRepository customerPinAuthRepository;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    // === 정책 상수(필요시 yml -> @Value 로 교체 가능) ===
    private static final int MAX_FAILED_ATTEMPTS = 5;  // 연속 실패 n회 시 잠금
    private static final int LOCK_MINUTES = 5;         // 잠금 지속 시간(분)
    private static final int PIN_LENGTH = 6;

    @Transactional
    public void setOrUpdatePin(Long customerId, String rawPin) {
        validatePinFormat(rawPin);

        String hash = passwordEncoder.encode(rawPin);
        LocalDateTime now = LocalDateTime.now(clock);

        CustomerPinAuth row = customerPinAuthRepository.findById(customerId).orElse(null);

        if (row == null) { // 초기 설정
            row = CustomerPinAuth.builder()
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
     * 승인 시 PIN 검증
     * - 잠금 중이면 PIN_LOCKED
     * - 미설정이면 PIN_NOT_SET
     * - 불일치면 실패 카운트 증가/임계 도달 시 잠금 → false
     * - 일치면 실패 카운트 초기화/성공시각 갱신(+필요시 재해시) → true
     */
    @Transactional
    public boolean verify(Long customerId, String rawPin) {
        if (rawPin == null || rawPin.isBlank()) {
            throw new CustomException(ErrorCode.PIN_REQUIRED); // 결제 비밀번호(PIN)는 필수입니다.
        }

        CustomerPinAuth row = customerPinAuthRepository.findById(customerId)
                .orElseThrow(() -> new CustomException(ErrorCode.PIN_NOT_SET)); // 설정된 결제 비밀번호(PIN)가 없습니다.

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

        // 성공 처리: 실패 카운터 리셋 + 성공 시각 기록
        row.setFailedCount(0);
        row.setLockedUntil(null);
        row.setLastVerifyAt(now);
        row.setUpdatedAt(now);

        customerPinAuthRepository.save(row);
        return true;
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