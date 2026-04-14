package com.ssafy.keeping.domain.payment.transactions.constant;

public enum TransactionType {
    CHARGE, // 충전
    USE, // 사용
    TRANSFER_IN, // 공유
    TRANSFER_OUT, // 회수
    CANCEL_CHARGE, // 카드 결제 취소
    CANCEL_USE, // 포인트 사용 취소
    REFUND // 환불 (UNCERTAIN 상태 복구용)
}