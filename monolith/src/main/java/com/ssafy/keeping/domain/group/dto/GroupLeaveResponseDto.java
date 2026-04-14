package com.ssafy.keeping.domain.group.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class GroupLeaveResponseDto {
    private final Long groupId;
    private final Long customerId;
    private final long refunded;       // 본인 환급액
    private final long indivBalance;   // 본인 개인지갑 총잔액
    private final LocalDateTime leftAt;
}
