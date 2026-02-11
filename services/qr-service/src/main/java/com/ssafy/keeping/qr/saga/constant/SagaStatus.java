package com.ssafy.keeping.qr.saga.constant;

/**
 * Saga 로그 상태
 */
public enum SagaStatus {
    PENDING,     // 처리 대기
    PROCESSING,  // 처리 중
    COMPLETED,   // 완료
    FAILED       // 최대 재시도 초과
}
