package com.ssafy.keeping.domain.charge.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 선결제 예약 요청 DTO
 * 1단계: 충전 금액을 서버에 먼저 등록
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrepaymentReserveRequest {

    /**
     * 충전 금액
     */
    @NotNull(message = "충전 금액은 필수입니다.")
    @Positive(message = "충전 금액은 양수여야 합니다.")
    private Long amount;

    /**
     * 주문명 (선택, 없으면 자동 생성)
     */
    private String orderName;
}
