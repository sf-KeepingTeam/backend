package com.ssafy.keeping.domain.store.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Store 이름 변경 이벤트 페이로드
 * Pattern 3 (데이터 복제) - 이벤트 기반 동기화
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoreNameChangedPayload {
    private Long storeId;
    private String oldStoreName;
    private String newStoreName;
}
