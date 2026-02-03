package com.ssafy.keeping.qrpayment.domain.qr.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;
import org.springframework.data.redis.core.TimeToLive;
import org.springframework.data.redis.core.index.Indexed;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@RedisHash("qrToken")
public class QrToken {

    @Id
    private String tokenId;

    @Indexed
    private Long walletId;

    private Long customerId;
    private String mode;
    private Long bindStoreId;

    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;

    @TimeToLive(unit = TimeUnit.SECONDS)
    private Long ttl;

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }
}
