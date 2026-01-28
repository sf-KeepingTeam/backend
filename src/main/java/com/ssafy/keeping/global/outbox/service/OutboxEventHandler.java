package com.ssafy.keeping.global.outbox.service;

import com.ssafy.keeping.global.outbox.model.OutboxEvent;

/**
 * Outbox 이벤트 핸들러 인터페이스
 *
 * 각 도메인에서 이 인터페이스를 구현하여 이벤트를 처리합니다.
 *
 * 예시:
 * <pre>
 * @Component
 * public class CustomerCreatedEventHandler implements OutboxEventHandler {
 *     @Override
 *     public String getEventType() {
 *         return "CustomerCreated";
 *     }
 *
 *     @Override
 *     public void handle(OutboxEvent event) {
 *         CustomerCreatedPayload payload = parsePayload(event.getPayload());
 *         // 지갑 생성, 알림 전송 등
 *     }
 * }
 * </pre>
 */
public interface OutboxEventHandler {

    /**
     * 이 핸들러가 처리하는 이벤트 유형
     * @return 이벤트 유형 문자열 (예: "CustomerCreated", "GroupDisbandInitiated")
     */
    String getEventType();

    /**
     * 이벤트 처리
     * @param event 처리할 Outbox 이벤트
     * @throws Exception 처리 중 발생한 예외 (재시도됨)
     */
    void handle(OutboxEvent event) throws Exception;
}
