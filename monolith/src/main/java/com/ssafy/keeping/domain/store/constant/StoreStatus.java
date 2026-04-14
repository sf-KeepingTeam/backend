package com.ssafy.keeping.domain.store.constant;

public enum StoreStatus {
    ACTIVE, // 승인, 현재 운영중
    SUSPENDED, // 점주가 탈퇴했거나 일정 문제로 임시 중단 된 상황
    DELETED // 정리가 다 된 후 삭제 처리
}
