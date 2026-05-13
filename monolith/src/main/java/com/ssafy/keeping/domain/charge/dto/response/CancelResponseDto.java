package com.ssafy.keeping.domain.charge.dto.response;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class CancelResponseDto {
    
    private Long cancelTransactionId;
    private String transactionUniqueNo;
    private Long cancelAmount;
    private LocalDateTime cancelTime;
    private Long remainingBalance;
}