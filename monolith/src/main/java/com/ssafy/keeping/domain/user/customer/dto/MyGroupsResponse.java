package com.ssafy.keeping.domain.user.customer.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class MyGroupsResponse {

    private final List<Long> groupIds;

    private final int count;

    public static MyGroupsResponse of(List<Long> groupIds) {
        return MyGroupsResponse.builder()
                .groupIds(groupIds)
                .count(groupIds != null ? groupIds.size() : 0)
                .build();
    }

}