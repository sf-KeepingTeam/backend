package com.ssafy.keeping.domain.auth.signup.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.domain.wallet.service.WalletService;
import com.ssafy.keeping.global.outbox.model.OutboxEvent;
import com.ssafy.keeping.global.outbox.service.OutboxEventHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * CustomerCreated 이벤트 핸들러
 * 고객 생성 후 필요한 후속 작업 수행:
 * - 개인 지갑 생성
 * - (추후) FCM 토큰 저장, 환영 알림 등
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CustomerCreatedEventHandler implements OutboxEventHandler {

    private final WalletService walletService;
    private final ObjectMapper objectMapper;

    @Override
    public String getEventType() {
        return "CustomerCreated";
    }

    @Override
    public void handle(OutboxEvent event) throws Exception {
        CustomerCreatedPayload payload = objectMapper.readValue(
                event.getPayload(), CustomerCreatedPayload.class);

        log.info("[이벤트] CustomerCreated 처리 시작 - customerId: {}", payload.getCustomerId());

        // 1. 개인 지갑 생성 (멱등성 보장)
        walletService.createOrGetIndividualWallet(payload.getCustomerId());

        log.info("[이벤트] CustomerCreated 처리 완료 - customerId: {}", payload.getCustomerId());
    }
}
