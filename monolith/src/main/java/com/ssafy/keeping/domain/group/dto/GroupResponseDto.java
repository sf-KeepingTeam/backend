package com.ssafy.keeping.domain.group.dto;

public record GroupResponseDto(
        Long groupId, String groupName,
        String groupDescription, String groupCode,
        Long walletId
) {}
