package com.ssafy.keeping.domain.group.dto;

public record GroupLeaderChangeResponseDto(
        Long groupId, Long groupMemberId, String customerName
) {}
