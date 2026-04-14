package com.ssafy.keeping.domain.idempotency.repository;

import com.ssafy.keeping.domain.idempotency.constant.IdemActorType;
import com.ssafy.keeping.domain.idempotency.model.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {
    // 특정 사용자/주체가 특정 경로에 대해 특정 멱등키로 요청을 한 적 있는지 찾는 쿼리
    Optional<IdempotencyKey> findByActorTypeAndActorIdAndPathAndKeyUuid(IdemActorType actorType, Long actorId, String path, UUID keyUuid);

    // 멱등성 키 UUID로만 조회 (롤백 이벤트 처리용)
    Optional<IdempotencyKey> findByKeyUuid(UUID keyUuid);
}
