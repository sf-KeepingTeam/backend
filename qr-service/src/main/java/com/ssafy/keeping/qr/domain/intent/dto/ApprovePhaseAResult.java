package com.ssafy.keeping.qr.domain.intent.dto;

import com.ssafy.keeping.qr.domain.idempotency.model.IdempotentResult;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Getter;

/**
 * TX-A (prepareApproval) 의 결과를 NO-TX 구간으로 전달하는 값 객체.
 *
 * <p>JPA 엔티티를 트랜잭션 경계 밖으로 꺼내지 않기 위해
 * 필요한 스칼라 값만 복사한다.
 */
@Getter
@Builder
public class ApprovePhaseAResult {

    // ── 조기 반환 (멱등 replay / 202 IN_PROGRESS) ──
    /** non-null 이면 호출자는 이 값을 즉시 반환하고 이후 단계를 건너뛴다. */
    private final IdempotentResult<PaymentIntentDetailResponse> earlyReturn;

    // ── 만료 감지 ──
    /**
     * true 면 intent 가 이미 만료됐다는 뜻이다.
     *
     * <p>TX-A 안에서 EXPIRED 전이를 하고 예외를 던지면 전이까지 롤백되므로,
     * TX-A 는 이 플래그만 세워 커밋하고 호출자가
     * {@code finalizeExpired(REQUIRES_NEW)} 로 전이시킨 뒤 예외를 던진다.
     * (실패 처리를 TX 밖으로 미루는 finalizeDeclined 와 같은 규약)
     *
     * <p>이때 유효한 필드는 {@code intentId / intentPublicId / customerId /
     * expiresAt / idemSlotId} 뿐이다.
     */
    private final boolean expired;

    // ── intent 스냅샷 (earlyReturn == null 일 때만 유효) ──
    private final Long intentId;
    private final UUID intentPublicId;
    private final Long customerId;
    private final Long walletId;
    private final Long storeId;
    private final Long amount;
    private final LocalDateTime expiresAt;

    // ── 멱등 슬롯 식별자 ──
    private final Long idemSlotId;

    // ── items 스냅샷 (메모리 복사본) ──
    private final List<PaymentIntentItemView> itemViews;

    /** 조기 반환이 필요한지 여부 */
    public boolean hasEarlyReturn() {
        return earlyReturn != null;
    }
}
