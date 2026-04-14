package com.ssafy.keeping.qr.domain.qr.dto;

import com.ssafy.keeping.qr.domain.qr.model.QrScanSession;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * QR 스캔 응답
 * QR 토큰 스캔 성공 시 반환되는 세션 정보
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QrScanResponse {

    private String sessionToken;
    private Long walletId;
    private Long customerId;
    private Long bindStoreId;
    private LocalDateTime expiresAt;
    private int ttlSeconds;

    public static QrScanResponse from(QrScanSession session, int ttlSeconds) {
        return QrScanResponse.builder()
                .sessionToken(session.getSessionToken())
                .walletId(session.getWalletId())
                .customerId(session.getCustomerId())
                .bindStoreId(session.getBindStoreId())
                .expiresAt(session.getExpiresAt())
                .ttlSeconds(ttlSeconds)
                .build();
    }
}
