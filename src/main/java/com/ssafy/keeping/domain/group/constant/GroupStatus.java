package com.ssafy.keeping.domain.group.constant;

/**
 * 그룹 상태
 * Saga 패턴을 위한 상태 관리
 */
public enum GroupStatus {
    ACTIVE,      // 활성 상태
    DISBANDING,  // 해산 진행 중
    DISBANDED    // 해산 완료
}
