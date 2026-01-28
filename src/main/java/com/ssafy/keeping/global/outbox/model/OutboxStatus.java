package com.ssafy.keeping.global.outbox.model;

/**
 * Outbox 이벤트 상태
 */
public enum OutboxStatus {
    PENDING,    // 발행 대기
    PUBLISHED,  // 발행 완료
    FAILED      // 발행 실패
}
