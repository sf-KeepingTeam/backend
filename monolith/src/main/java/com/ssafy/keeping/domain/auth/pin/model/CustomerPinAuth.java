package com.ssafy.keeping.domain.auth.pin.model;

import jakarta.persistence.*;
import jakarta.persistence.Table;
import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "customer_pin_auth")
public class CustomerPinAuth {

    @Id
    @Column(name = "customer_id")
    private Long customerId;                    // PK & FK (1:1 고정)

    @Version
    @Column(name = "version")
    private Long version;                       // 낙관적 락 (동시 요청 시 fail_count 정확성 보장)

    @Column(name = "pin_hash", nullable = false, length = 255)
    private String pinHash;                     // 해시만 저장(평문 금지)

    @Column(name = "failed_count", nullable = false)
    private int failedCount;                    // 연속 실패 횟수

    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;          // 잠금 해제 시각(쿨다운 끝)

    @Column(name = "set_at", nullable = false)
    private LocalDateTime setAt;                // 현재 PIN 설정 시각

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;            // 마지막 변경 시각

    @Column(name = "last_verify_at")
    private LocalDateTime lastVerifyAt;         // 마지막 성공 검증 시각
}
