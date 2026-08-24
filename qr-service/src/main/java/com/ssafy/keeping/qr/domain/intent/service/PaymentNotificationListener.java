package com.ssafy.keeping.qr.domain.intent.service;

import com.ssafy.keeping.qr.acl.NotificationClient;
import com.ssafy.keeping.qr.acl.StoreClient;
import com.ssafy.keeping.qr.acl.dto.StoreResponse;
import com.ssafy.keeping.qr.domain.intent.event.PaymentApprovedEvent;
import com.ssafy.keeping.qr.domain.intent.event.PaymentRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 결제 알림을 트랜잭션 커밋 이후 비동기로 발송한다.
 *
 * <p>알림 실패가 결제에 영향을 주면 안 되므로, 리스너 전체를 try/catch 로 감싸고
 * 예외 시 경고 로그만 남긴다.
 *
 * <p>transport=kafka 이면 이 빈은 생성되지 않는다.
 * 알림은 아웃박스 → Kafka 폴러(#52) → monolith 컨슈머(#53) 경로로 전달된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "payment.notification.transport", havingValue = "http", matchIfMissing = true)
public class PaymentNotificationListener {

    private final NotificationClient notificationClient;
    private final StoreClient storeClient;

    /**
     * 결제 의도 생성 후 → 고객에게 PAYMENT_REQUEST 알림.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Async("notificationExecutor")
    public void onPaymentRequested(PaymentRequestedEvent event) {
        try {
            StoreResponse store = storeClient.getStore(event.getStoreId()).orElse(null);
            String storeName = store != null ? store.getStoreName() : "매장";

            String content = String.format(
                    "%s에서 %,d원 결제 요청이 도착했습니다.", storeName, event.getAmount());
            notificationClient.sendToCustomer(
                    event.getCustomerId(), "PAYMENT_REQUEST", content);

            log.info("결제 요청 알림 전송 완료 - 손님ID: {}, 결제 금액: {}",
                    event.getCustomerId(), event.getAmount());
        } catch (Exception e) {
            log.warn("결제 요청 알림 전송 실패 - 손님ID: {}, error: {}",
                    event.getCustomerId(), e.getMessage());
        }
    }

    /**
     * 결제 승인 후 → 점주(PAYMENT_APPROVED) + 고객(PAYMENT_APPROVED) 알림.
     *
     * <p>{@code storeClient.getStore()} 도 이 리스너 안에서 호출한다 (HTTP 호출을 TX 밖으로 옮김).
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Async("notificationExecutor")
    public void onPaymentApproved(PaymentApprovedEvent event) {
        try {
            StoreResponse store = storeClient.getStore(event.getStoreId()).orElse(null);
            String storeName = store != null ? store.getStoreName() : "매장";
            Long ownerId = store != null ? store.getOwnerId() : null;

            // 점주 알림
            if (ownerId != null) {
                String ownerContent = String.format(
                        "%,d원 결제가 완료되었습니다.", event.getAmount());
                notificationClient.sendToOwner(ownerId, "PAYMENT_APPROVED", ownerContent);
            }

            // 고객 알림
            String customerContent = String.format(
                    "%s에서 %,d원 결제가 완료되었습니다.", storeName, event.getAmount());
            notificationClient.sendToCustomer(
                    event.getCustomerId(), "PAYMENT_APPROVED", customerContent);

            log.info("결제 승인 알림 전송 완료 - 손님ID: {}, 결제 금액: {}",
                    event.getCustomerId(), event.getAmount());
        } catch (Exception e) {
            log.warn("결제 승인 알림 전송 실패 - 손님ID: {}, error: {}",
                    event.getCustomerId(), e.getMessage());
        }
    }
}
