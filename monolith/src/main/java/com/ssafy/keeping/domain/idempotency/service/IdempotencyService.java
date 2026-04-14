package com.ssafy.keeping.domain.idempotency.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.ssafy.keeping.domain.idempotency.constant.IdemActorType;
import com.ssafy.keeping.domain.idempotency.constant.IdemStatus;
import com.ssafy.keeping.domain.idempotency.dto.IdemBegin;
import com.ssafy.keeping.domain.idempotency.model.IdempotencyKey;
import com.ssafy.keeping.domain.idempotency.repository.IdempotencyKeyRepository;
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
     * - 같은 범위(ActorType+ActorId+Path+KeyUuid)에 기존 레코드가 있으면 그대로 리턴
     * - 없으면 IN_PROGRESS로 새로 만들고 리턴
     */
    @Transactional
    public IdemBegin beginOrLoad(IdemActorType actorType,
                                 Long actorId,
                                 String method,
                                 String path,
                                 UUID keyUuid,
                                 byte[] bodyHash) {
        Optional<IdempotencyKey> existing = idempotencyKeyRepository.findByActorTypeAndActorIdAndPathAndKeyUuid(actorType, actorId, path, keyUuid);

        if (existing.isPresent()) return new IdemBegin(existing.get(), false); // 기존 레코드가 있으면 그대로 return

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
        return new IdemBegin(idempotencyKeyRepository.save(idem), true); // 새로 만들었음
    }

    /**
     * 요청 본문(bodyHash) 충돌 여부 확인
     * - 동일 멱등키로 처음 요청과 다른 본문이 오면 true(충돌)
     */
    public boolean isBodyConflict(IdempotencyKey row, byte[] bodyHash) {
        if (row == null) return false;
        return !java.util.Arrays.equals(row.getBodyHash(), bodyHash);
    }

    /**
     * 처리 완료 기록(DONE) + 응답 스냅샷 저장
     * - 이후 동일 키로 재호출 시 이 응답을 재생(replay) 가능
     */
    @Transactional
    public void complete(IdempotencyKey row, int httpStatus, Object responseBody, UUID resourcePublicId) {
        row.setStatus(IdemStatus.DONE);
        row.setHttpStatus(httpStatus);
        row.setIntentPublicId(resourcePublicId);
        try {
            // String json = canonicalObjectMapper.writeValueAsString(responseBody); // JSON 직렬화
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

        // String json = canonicalObjectMapper.writeValueAsString(responseBody); // throws 가능
        row.setResponseJson(canonicalObjectMapper.valueToTree(responseBody));

        idempotencyKeyRepository.save(row);
    }

    @Transactional
    public void completeCharge(IdempotencyKey row, int httpStatus, Object responseBody) {
        row.setStatus(IdemStatus.DONE);
        row.setHttpStatus(httpStatus);
        try {
            row.setResponseJson(canonicalObjectMapper.valueToTree(responseBody));
        } catch (Exception e) {
            log.warn("Response 직렬화 실패", e);
        }

        idempotencyKeyRepository.save(row);
    }

    /**
     * SHA-256 유틸 (정규화된 요청 바디 문자열 → 32바이트 해시)
     * - 반드시 먼저 원문 정규화가 필요!!
     * - JSON 키 순서, 공백, 기본값 누락 등으로 같은 의미의 요청이 다른 바이트열이 되기 쉬움.
     */
    public static byte[] sha256(String bodyCanonical) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(bodyCanonical.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 폴백 경로: DONE만 기록(스냅샷 없이) → 재시도 시 리소스 재조회로 재생 */
    @Transactional
    public void completeWithoutSnapshot(IdempotencyKey row,
                                        int httpStatus,
                                        UUID intentPublicId) {
        row.setStatus(IdemStatus.DONE);
        row.setHttpStatus(httpStatus);
        row.setResponseJson(null); // 스냅샷 없음 (직렬화 실패)
        row.setIntentPublicId(intentPublicId);

        idempotencyKeyRepository.save(row);
    }

    /**
     * 멱등성 키 문자열로 조회 (롤백 이벤트 처리용)
     * @param keyString UUID 문자열 형태의 멱등성 키
     * @return 해당 멱등성 키 레코드, 없거나 잘못된 형식이면 null
     */
    public IdempotencyKey findByKeyString(String keyString) {
        try {
            UUID keyUuid = UUID.fromString(keyString);
            return idempotencyKeyRepository.findByKeyUuid(keyUuid).orElse(null);
        } catch (IllegalArgumentException e) {
            log.warn("잘못된 멱등성 키 형식: {}", keyString);
            return null;
        }
    }

}