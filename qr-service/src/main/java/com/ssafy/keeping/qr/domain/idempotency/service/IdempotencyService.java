package com.ssafy.keeping.qr.domain.idempotency.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.qr.domain.idempotency.constant.IdemActorType;
import com.ssafy.keeping.qr.domain.idempotency.constant.IdemStatus;
import com.ssafy.keeping.qr.domain.idempotency.dto.IdemBegin;
import com.ssafy.keeping.qr.domain.idempotency.model.IdempotencyKey;
import com.ssafy.keeping.qr.domain.idempotency.repository.IdempotencyKeyRepository;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final Clock clock;

    @Qualifier("canonicalObjectMapper")
    private final ObjectMapper canonicalObjectMapper;

    /**
     * 멱등키 '선점 또는 로드'
     */
    @Transactional
    public IdemBegin beginOrLoad(IdemActorType actorType,
                                 Long actorId,
                                 String method,
                                 String path,
                                 UUID keyUuid,
                                 byte[] bodyHash) {
        Optional<IdempotencyKey> existing = idempotencyKeyRepository
                .findByActorTypeAndActorIdAndPathAndKeyUuid(actorType, actorId, path, keyUuid);

        if (existing.isPresent()) return new IdemBegin(existing.get(), false);

        IdempotencyKey idem = IdempotencyKey.builder()
                .keyUuid(keyUuid)
                .actorType(actorType)
                .actorId(actorId)
                .method(method)
                .path(path)
                .bodyHash(bodyHash)
                .status(IdemStatus.IN_PROGRESS)
                .createdAt(LocalDateTime.now(clock))
                .build();
        return new IdemBegin(idempotencyKeyRepository.save(idem), true);
    }

    /**
     * 요청 본문(bodyHash) 충돌 여부 확인
     */
    public boolean isBodyConflict(IdempotencyKey row, byte[] bodyHash) {
        if (row == null) return false;
        return !java.util.Arrays.equals(row.getBodyHash(), bodyHash);
    }

    /**
     * 처리 완료 기록(DONE) + 응답 스냅샷 저장
     */
    @Transactional
    public void complete(IdempotencyKey row, int httpStatus, Object responseBody, UUID resourcePublicId) {
        row.setStatus(IdemStatus.DONE);
        row.setHttpStatus(httpStatus);
        row.setIntentPublicId(resourcePublicId);
        try {
            row.setResponseJson(canonicalObjectMapper.valueToTree(responseBody));
        } catch (Exception e) {
            log.warn("Response 직렬화 실패", e);
        }

        idempotencyKeyRepository.save(row);
    }

    @Transactional
    public void completeStrict(IdempotencyKey row,
                               int httpStatus,
                               Object responseBody,
                               UUID resourcePublicId) throws JsonProcessingException {
        row.setStatus(IdemStatus.DONE);
        row.setHttpStatus(httpStatus);
        row.setIntentPublicId(resourcePublicId);
        row.setResponseJson(canonicalObjectMapper.valueToTree(responseBody));
        idempotencyKeyRepository.save(row);
    }

    @Transactional
    public void completeWithoutSnapshot(IdempotencyKey row,
                                        int httpStatus,
                                        UUID intentPublicId) {
        row.setStatus(IdemStatus.DONE);
        row.setHttpStatus(httpStatus);
        row.setResponseJson(null);
        row.setIntentPublicId(intentPublicId);
        idempotencyKeyRepository.save(row);
    }

    /**
     * SHA-256 유틸
     */
    public static byte[] sha256(String bodyCanonical) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(bodyCanonical.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
