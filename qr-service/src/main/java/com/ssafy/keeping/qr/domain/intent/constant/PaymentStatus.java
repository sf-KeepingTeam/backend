package com.ssafy.keeping.qr.domain.intent.constant;

public enum PaymentStatus {
    PENDING,      // 점주가 금액 입력해 생성됨
    APPROVED,     // 손님 승인
    DECLINED,     // 손님 거절
    CANCELED,     // 점주/시스템 취소
    EXPIRED,      // 유효시간 만료(연결된 QR 만료/Intent 자체 만료)
    UNCERTAIN,    // 타임아웃 (결과 불명)
    ROLLED_BACK   // 보상 트랜잭션 완료
}
