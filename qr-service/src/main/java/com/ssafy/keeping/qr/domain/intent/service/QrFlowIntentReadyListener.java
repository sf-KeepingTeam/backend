package com.ssafy.keeping.qr.domain.intent.service;

import com.ssafy.keeping.qr.domain.intent.event.QrFlowIntentReadyEvent;
import com.ssafy.keeping.qr.domain.qr.repository.QrFlowRedisStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * initiate() 커밋 후 QR 플로우 Redis 기록 + 롱폴링 대기자 해소.
 *
 * <p>@Async 를 붙이지 않는다 — DiscardPolicy 스레드풀에 맡기면 Redis 쓰기가 drop 되어
 * 폴링 fallback 키가 영원히 생성되지 않을 수 있다.
 * 동기 실행 비용은 Redis 1회 SET (~1 ms WRITE_THROUGH 기본) + waiter resolve 이며,
 * WRITE_THROUGH(기본 모드) 에서 총 ~2 ms 이하다. 단, @TransactionalEventListener(AFTER_COMMIT)
 * 는 TX 커밋 후 리스너를 실행한 뒤 컨트롤러로 복귀하므로, 점주 응답이 리스너 실행 시간만큼
 * 지연된다. NONE 모드에서 StoreClient HTTP 호출이 발생하면 ~50 ms 추가될 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QrFlowIntentReadyListener {

    private final QrFlowRedisStore qrFlowRedisStore;
    private final IntentWaitRegistry intentWaitRegistry;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIntentReady(QrFlowIntentReadyEvent event) {
        log.debug("[QR_FLOW] intent 도착 — tokenId={} intentPublicId={}",
                event.tokenId(), event.intentPublicId());

        // 1. Redis 에 intent 도착 기록 (폴링 fallback 경로를 위한 영속 신호)
        try {
            qrFlowRedisStore.saveIntentArrival(
                    event.tokenId(), event.intentPublicId(), event.customerId());
        } catch (Exception e) {
            log.warn("[QR_FLOW] Redis intent 기록 실패 — tokenId={} error={}",
                    event.tokenId(), e.getMessage());
            // 기록 실패해도 이미 등록된 waiter 는 즉시 해소를 시도한다.
        }

        // 2. 대기 중인 waiter 즉시 해소 (push path — 폴링 200 ms 기다릴 필요 없음)
        intentWaitRegistry.resolve(
                event.tokenId(),
                event.intentPublicId(),
                event.customerId(),
                event.storeId(),
                event.amount(),
                event.items());
    }
}
