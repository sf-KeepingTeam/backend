package com.ssafy.keeping.qr.domain.qr.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.qr.domain.qr.model.QrToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class QrTokenRedisRepository {

    private static final String PREFIX = "qrToken:";
    private static final String WALLET_INDEX_PREFIX = "qrToken:wallet:";
    private static final long TTL_SECONDS = 10L;

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;

    public void save(QrToken token) {
        try {
            String key = PREFIX + token.getTokenId();
            String json = objectMapper.writeValueAsString(token);
            redisTemplate.opsForValue().set(key, json, Duration.ofSeconds(TTL_SECONDS));

            // walletId 인덱스 (기존 findByWalletId 대체)
            if (token.getWalletId() != null) {
                String walletKey = WALLET_INDEX_PREFIX + token.getWalletId();
                redisTemplate.opsForValue().set(walletKey, token.getTokenId(), Duration.ofSeconds(TTL_SECONDS));
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException("QrToken 직렬화 실패", e);
        }
    }

    /**
     * GETDEL - 조회와 삭제를 단일 원자적 커맨드로 수행
     */
    public Optional<QrToken> consumeToken(String tokenId) {
        String key = PREFIX + tokenId;
        String json = redisTemplate.opsForValue().getAndDelete(key);
        if (json == null) {
            return Optional.empty();
        }
        try {
            QrToken token = objectMapper.readValue(json, QrToken.class);
            // walletId 인덱스도 정리
            if (token.getWalletId() != null) {
                redisTemplate.delete(WALLET_INDEX_PREFIX + token.getWalletId());
            }
            return Optional.of(token);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("QrToken 역직렬화 실패", e);
        }
    }

    public Optional<QrToken> findByTokenId(String tokenId) {
        String key = PREFIX + tokenId;
        String json = redisTemplate.opsForValue().get(key);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(json, QrToken.class));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("QrToken 역직렬화 실패", e);
        }
    }

    public Optional<QrToken> findByWalletId(Long walletId) {
        String walletKey = WALLET_INDEX_PREFIX + walletId;
        String tokenId = redisTemplate.opsForValue().get(walletKey);
        if (tokenId == null) {
            return Optional.empty();
        }
        return findByTokenId(tokenId);
    }

    public void delete(QrToken token) {
        redisTemplate.delete(PREFIX + token.getTokenId());
        if (token.getWalletId() != null) {
            redisTemplate.delete(WALLET_INDEX_PREFIX + token.getWalletId());
        }
    }

    public void deleteByTokenId(String tokenId) {
        findByTokenId(tokenId).ifPresent(this::delete);
    }
}
