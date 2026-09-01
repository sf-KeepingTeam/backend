package com.ssafy.keeping.qr.domain.intent.service;

import com.ssafy.keeping.qr.acl.StoreClient;
import com.ssafy.keeping.qr.domain.intent.dto.IntentArrivalCacheValue;
import com.ssafy.keeping.qr.domain.intent.event.QrFlowIntentReadyEvent;
import com.ssafy.keeping.qr.domain.qr.repository.QrFlowRedisStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * initiate() 커밋 후 QR 플로우 Redis 기록 + 롱폴링 대기자 해소.
 *
 * <p>@Async 를 붙이지 않는다 — DiscardPolicy 스레드풀에 맡기면 Redis 쓰기가 drop 되어
 * 폴링 fallback 키가 영원히 생성되지 않을 수 있다.
 * {@code @TransactionalEventListener(AFTER_COMMIT)} 은 TX 커밋 후 리스너를 끝낸 뒤
 * 컨트롤러로 복귀하므로, 점주 응답이 리스너 실행 시간만큼 지연된다. 실측 필요.
 * ({@code CACHE_MODE=NONE} 이면 여기서 StoreClient HTTP 가 추가된다.)
 *
 * <p>활성화: {@code qr.intent-wait.enabled=true} 일 때만 빈이 등록된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "qr.intent-wait.enabled", havingValue = "true")
public class QrFlowIntentReadyListener {

    private final QrFlowRedisStore qrFlowRedisStore;
    private final IntentWaitRegistry intentWaitRegistry;
    private final StoreClient storeClient;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIntentReady(QrFlowIntentReadyEvent event) {
        log.debug("[QR_FLOW] intent 도착 — tokenId={} intentPublicId={}",
                event.tokenId(), event.intentPublicId());

        // storeName 을 한 번만 조회한다.
        // poll 경로에서 매번 StoreClient 를 호출하던 것을 캐시에 포함시켜 제거.
        String storeName = fetchStoreName(event.storeId());

        // 캐시 값 구성 (DB 조회 없이 이벤트 필드 직접 사용)
        IntentArrivalCacheValue cached = IntentArrivalCacheValue.of(
                event.intentPublicId(), event.customerId(),
                event.amount(), storeName, event.items());

        // 1. Redis 에 intent 도착 기록 (폴링 fallback / 즉시 해소 경로를 위한 영속 신호)
        try {
            qrFlowRedisStore.saveIntentArrival(event.tokenId(), cached);
        } catch (Exception e) {
            log.warn("[QR_FLOW] Redis intent 기록 실패 — tokenId={} error={}",
                    event.tokenId(), e.getMessage());
        }

        // 2. approve 후 DEL 을 위한 역참조 키 저장 (i2t)
        try {
            qrFlowRedisStore.saveI2TMapping(
                    event.intentPublicId().toString(), event.tokenId());
        } catch (Exception e) {
            log.warn("[QR_FLOW] i2t 매핑 기록 실패 — intentPublicId={} error={}",
                    event.intentPublicId(), e.getMessage());
        }

        // 3. 대기 중인 waiter 즉시 해소 (push path — 폴링 200 ms 기다릴 필요 없음)
        intentWaitRegistry.resolve(
                event.tokenId(),
                event.intentPublicId(),
                event.customerId(),
                event.amount(),
                storeName,
                event.items());
    }

    private String fetchStoreName(Long storeId) {
        try {
            return storeClient.getStore(storeId)
                    .map(s -> s.getStoreName())
                    .orElse("매장");
        } catch (Exception e) {
            log.warn("[QR_FLOW] StoreClient 실패 — storeId={} fallback='매장'", storeId);
            return "매장";
        }
    }
}
