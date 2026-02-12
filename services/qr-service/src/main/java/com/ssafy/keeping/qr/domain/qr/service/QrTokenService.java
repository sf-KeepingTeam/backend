package com.ssafy.keeping.qr.domain.qr.service;

import com.ssafy.keeping.qr.common.exception.CustomException;
import com.ssafy.keeping.qr.common.exception.ErrorCode;
import com.ssafy.keeping.qr.domain.qr.dto.QrCreateRequest;
import com.ssafy.keeping.qr.domain.qr.dto.QrCreateResponse;
import com.ssafy.keeping.qr.domain.qr.model.QrToken;
import com.ssafy.keeping.qr.domain.qr.repository.QrTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class QrTokenService {

    private final QrTokenRepository qrTokenRepository;

    private static final int TTL_SECONDS = 10;

    /**
     * QR 토큰 생성
     */
    public QrCreateResponse createQrToken(QrCreateRequest request, Long customerId) {
        int ttl = TTL_SECONDS;

        // 기존 QR이 있으면 삭제 (중복 방지)
        qrTokenRepository.findByWalletId(request.getWalletId())
                .ifPresent(existing -> {
                    log.info("기존 QR 토큰 삭제: {}", existing.getTokenId());
                    qrTokenRepository.delete(existing);
                });

        // 새 QR 토큰 생성
        String tokenId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = now.plusSeconds(ttl);

        QrToken qrToken = QrToken.builder()
                .tokenId(tokenId)
                .walletId(request.getWalletId())
                .customerId(customerId)
                .bindStoreId(request.getBindStoreId())
                .createdAt(now)
                .expiresAt(expiresAt)
                .ttl((long) ttl)
                .build();

        qrTokenRepository.save(qrToken);
        log.info("QR 토큰 생성: tokenId={}, walletId={}, ttl={}초", tokenId, request.getWalletId(), ttl);

        return QrCreateResponse.from(tokenId, expiresAt, ttl);
    }

    /**
     * QR 토큰 조회 및 검증
     */
    public QrToken getValidToken(String tokenId) {
        QrToken token = qrTokenRepository.findByTokenId(tokenId)
                .orElseThrow(() -> new CustomException(ErrorCode.QR_NOT_FOUND));

        if (token.isExpired()) {
            throw new CustomException(ErrorCode.QR_EXPIRED);
        }

        return token;
    }

    /**
     * QR 토큰 조회 (Optional)
     */
    public Optional<QrToken> findToken(String tokenId) {
        return qrTokenRepository.findByTokenId(tokenId);
    }

    /**
     * QR 토큰 삭제 (결제 완료 후)
     */
    public void deleteToken(String tokenId) {
        qrTokenRepository.findByTokenId(tokenId)
                .ifPresent(qrTokenRepository::delete);
        log.info("QR 토큰 삭제: {}", tokenId);
    }
}
