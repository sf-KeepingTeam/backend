package com.ssafy.keeping.qr.domain.qr.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.TimeToLive;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * QR 스캔 세션
 * QR 토큰 스캔 후 발급되는 세션 토큰
 * - QR 토큰은 스캔 즉시 삭제 (재사용 방지)
 * - 세션 토큰으로 결제 요청 진행 (3분 유효)
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@RedisHash("qrScanSession")
public class QrScanSession {

    @Id
    private String sessionToken;

    private Long walletId;
    private Long customerId;
    private Long bindStoreId;

    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;

    @TimeToLive(unit = TimeUnit.SECONDS)
    private Long ttl;

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }
}
