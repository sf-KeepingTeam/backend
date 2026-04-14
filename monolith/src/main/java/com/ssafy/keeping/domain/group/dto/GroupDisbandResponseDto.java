package com.ssafy.keeping.domain.group.dto;


import java.time.LocalDateTime;
import java.util.Map;

// DTO: 멤버별 환급 결과 포함
public record GroupDisbandResponseDto(
        Long groupId,
        int memberCount,
        long totalRefunded,
        Map<Long, Long> refundedByMember, // key: customerId, value: refunded
        LocalDateTime disbandedAt
) {}
