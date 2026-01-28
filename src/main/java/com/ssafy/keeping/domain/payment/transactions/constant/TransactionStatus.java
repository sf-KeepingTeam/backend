package com.ssafy.keeping.domain.payment.transactions.constant;

/**
 * 트랜잭션 상태
 * 선생성 패턴을 위한 상태 관리
 */
public enum TransactionStatus {
    PENDING,    // 처리 중 (선생성된 상태)
    COMPLETED,  // 완료
    FAILED      // 실패
}
