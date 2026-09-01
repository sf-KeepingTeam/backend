package com.ssafy.keeping.qr.domain.intent.event;

import com.ssafy.keeping.qr.domain.intent.dto.PaymentIntentItemView;
import java.util.List;
import java.util.UUID;

/**
 * initiate() @Transactional 커밋 직후 발행되는 이벤트.
 *
 * <p>@TransactionalEventListener(AFTER_COMMIT) 리스너({@code QrFlowIntentReadyListener})가
 * Redis 에 intent 도착 기록 후 롱폴링 대기자를 즉시 해소한다.
 *
 * <p>엔티티를 담지 않고 필요한 값만 복사 — 커밋 후 lazy 로딩 방지.
 */
public record QrFlowIntentReadyEvent(
        String tokenId,
        UUID intentPublicId,
        Long customerId,
        Long storeId,
        Long amount,
        List<PaymentIntentItemView> items) {}
