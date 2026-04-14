package com.ssafy.keeping.domain.charge.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 선결제 승인 요청 DTO (3단계)
 * 토스 결제 완료 후 최종 승인 요청
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrepaymentConfirmRequest {

    /**
     * 토스에서 받은 paymentKey
     */
    @NotBlank(message = "paymentKey는 필수입니다.")
    private String paymentKey;

    /**
     * 주문 ID (예약 시 받은 orderId)
     */
    @NotBlank(message = "orderId는 필수입니다.")
    private String orderId;

    /**
     * 결제 금액 (예약 시 금액과 일치해야 함)
     */
    @NotNull(message = "금액은 필수입니다.")
    @Positive(message = "금액은 양수여야 합니다.")
    private Long amount;
}
