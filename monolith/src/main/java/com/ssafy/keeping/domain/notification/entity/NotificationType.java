package com.ssafy.keeping.domain.notification.entity;

/**
 * 알림 타입 enum
 * 각 도메인별로 알림 타입을 정의
 */
public enum NotificationType {
    
    // 결제/정산 관련
    POINT_CHARGE("포인트 충전"),
    PERSONAL_POINT_USE("개인 포인트 사용"),
    GROUP_POINT_USE("모임 포인트 사용"),
    POINT_CANCELED("포인트 사용 취소"),

    PAYMENT_APPROVED("결제 수락"),
    PAYMENT_REQUEST("포인트 결제 요청"),
    PAYMENT_CANCELED("결제 취소"),
    SETTLEMENT_COMPLETED("정산 완료"),
    
    // 그룹 관련
    GROUP_INVITE("모임 초대"),
    GROUP_JOIN_REQUEST("모임 가입 요청"),
    GROUP_JOIN_ACCEPTED("모임 가입 승인"),
    GROUP_JOIN_REJECTED("모임 가입 거절"),
    GROUP_JOINED("모임 참여 완료"),
    GROUP_LEADER_CHANGED("모임 리더 변경"),
    MEMBER_EXPELLED("모임원 내보내기"),
    GROUP_POINT_SHARED("모임 지갑에 포인트 공유"),
    GROUP_LEFT("모임 나가기"),
    GROUP_DISBANDED("모임 해체"),
    // 시스템 관련
    DLQ_NOTICE("DLQ 생성"),
    ANOMALY_DETECTED("이상거래 탐지");

    private final String displayName;

    NotificationType(String displayName) {
        this.displayName = displayName;
    }
    
    public String getDisplayName() {
        return displayName;
    }
}