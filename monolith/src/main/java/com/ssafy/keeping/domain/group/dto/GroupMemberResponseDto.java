package com.ssafy.keeping.domain.group.dto;

public record GroupMemberResponseDto(
        Long groupId, Long customerId, String customerName,
        boolean isLeader, Long groupMemberId
) {}
