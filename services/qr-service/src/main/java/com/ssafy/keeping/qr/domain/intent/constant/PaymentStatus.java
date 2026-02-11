package com.ssafy.keeping.qr.domain.intent.constant;

public enum PaymentStatus {
    PENDING,      // 점주가 금액 입력해 생성됨
    APPROVED,     // 손님 승인
    DECLINED,     // 손님 거절
    CANCELED,     // 점주/시스템 취소
    EXPIRED,      // 유효시간 만료(연결된 QR 만료/Intent 자체 만료)
}
