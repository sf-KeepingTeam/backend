package com.ssafy.keeping.domain.group.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * GroupDisbandInitiated 이벤트 페이로드
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupDisbandPayload {
    private Long groupId;
    private Long walletId;
    private List<Long> memberIds;
    private Long leaderId;
}
