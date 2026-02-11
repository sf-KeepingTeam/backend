package com.ssafy.keeping.qr.domain.qr.dto;

import com.ssafy.keeping.qr.domain.qr.model.QrToken;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QrTokenResponse {
    private String tokenId;
    private Long walletId;
    private Long customerId;
    private String mode;
    private Long bindStoreId;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;

    public static QrTokenResponse from(QrToken token) {
        return QrTokenResponse.builder()
                .tokenId(token.getTokenId())
                .walletId(token.getWalletId())
                .customerId(token.getCustomerId())
                .mode(token.getMode())
                .bindStoreId(token.getBindStoreId())
                .createdAt(token.getCreatedAt())
                .expiresAt(token.getExpiresAt())
                .build();
    }
}
