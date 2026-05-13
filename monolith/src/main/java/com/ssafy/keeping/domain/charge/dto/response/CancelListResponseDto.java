package com.ssafy.keeping.domain.charge.dto.response;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class CancelListResponseDto {
    
    private String transactionUniqueNo;
    private String storeName;
    private Long paymentAmount;
    private LocalDateTime transactionTime;
    private Long remainingBalance;
}