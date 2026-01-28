package com.ssafy.keeping.domain.store.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.domain.wallet.repository.WalletStoreBalanceRepository;
import com.ssafy.keeping.global.outbox.model.OutboxEvent;
import com.ssafy.keeping.global.outbox.service.OutboxEventHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * StoreNameChanged 이벤트 핸들러
 * Pattern 3 (데이터 복제) - 가게 이름 변경 시 스냅샷 동기화
 *
 * 처리 대상:
 * - WalletStoreBalance.storeNameSnapshot
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StoreNameChangedEventHandler implements OutboxEventHandler {

    private final WalletStoreBalanceRepository balanceRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String getEventType() {
        return "StoreNameChanged";
    }

    @Override
    @Transactional
    public void handle(OutboxEvent event) throws Exception {
        StoreNameChangedPayload payload = objectMapper.readValue(
                event.getPayload(), StoreNameChangedPayload.class);

        log.info("[이벤트] StoreNameChanged 처리 시작 - storeId: {}, {} -> {}",
                payload.getStoreId(), payload.getOldStoreName(), payload.getNewStoreName());

        // WalletStoreBalance의 storeNameSnapshot 업데이트
        int updatedCount = balanceRepository.updateStoreNameSnapshot(
                payload.getStoreId(), payload.getNewStoreName());

        log.info("[이벤트] StoreNameChanged 처리 완료 - storeId: {}, 업데이트된 Balance 수: {}",
                payload.getStoreId(), updatedCount);
    }
}
