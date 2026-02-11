package com.ssafy.keeping.qr.saga.constant;

/**
 * Saga 이벤트 타입
 */
public enum SagaEventType {
    FUNDS_CAPTURE,           // 자금 캡처 (필수)
    FUNDS_RESTORE,           // 자금 복원 (보상)
    NOTIFICATION_REQUEST,    // 결제 요청 알림
    NOTIFICATION_APPROVED    // 결제 승인 알림
}
