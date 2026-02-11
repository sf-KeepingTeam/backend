package com.ssafy.keeping.qr.domain.idempotency.repository;

import com.ssafy.keeping.qr.domain.idempotency.constant.IdemActorType;
import com.ssafy.keeping.qr.domain.idempotency.model.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

    Optional<IdempotencyKey> findByActorTypeAndActorIdAndPathAndKeyUuid(
            IdemActorType actorType, Long actorId, String path, UUID keyUuid);
}
