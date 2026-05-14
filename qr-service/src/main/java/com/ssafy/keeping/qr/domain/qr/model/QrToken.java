package com.ssafy.keeping.qr.domain.qr.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QrToken {

    private String tokenId;

    private Long walletId;

    private Long customerId;
    private Long bindStoreId;

    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;

    private Long ttl;

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }
}
