package com.ssafy.keeping.domain.charge.canonical;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 선결제 요청 정규화 DTO
 * 멱등성 체크를 위한 요청 본문 해시 생성용
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CanonicalPrepayment {

    private String paymentKey;
    private String orderId;
    private Long amount;
}
