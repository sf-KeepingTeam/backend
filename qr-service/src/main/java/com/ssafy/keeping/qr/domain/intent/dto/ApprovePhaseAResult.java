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
