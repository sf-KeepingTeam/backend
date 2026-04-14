package com.ssafy.keeping.domain.group.dto;

public record GroupMaskingResponseDto(
        Long groupId, String groupName,
        String groupDescription, String leaderMaskingName
) {}
