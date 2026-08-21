package com.ssafy.keeping.qr.domain.idempotency.repository;

import com.ssafy.keeping.qr.domain.idempotency.constant.IdemActorType;
import com.ssafy.keeping.qr.domain.idempotency.constant.IdemStatus;
import com.ssafy.keeping.qr.domain.idempotency.model.IdempotencyKey;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {

  Optional<IdempotencyKey> findByActorTypeAndActorIdAndPathAndKeyUuid(
      IdemActorType actorType, Long actorId, String path, UUID keyUuid);

  List<IdempotencyKey> findByStatusAndCreatedAtBefore(IdemStatus status, LocalDateTime threshold);
}
