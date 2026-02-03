package com.ssafy.keeping.qrpayment.domain.qr.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor
public class QrCreateResponse {

    private String tokenId;
    private LocalDateTime expiresAt;
    private Integer ttlSeconds;

    public static QrCreateResponse from(String tokenId, LocalDateTime expiresAt, int ttl) {
        return QrCreateResponse.builder()
                .tokenId(tokenId)
                .expiresAt(expiresAt)
                .ttlSeconds(ttl)
                .build();
    }
}
