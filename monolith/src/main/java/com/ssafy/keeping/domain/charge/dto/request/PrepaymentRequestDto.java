package com.ssafy.keeping.domain.charge.dto.request;

import lombok.*;

import jakarta.validation.constraints.*;

/**
 * 선결제 요청 DTO
 * 토스 결제위젯 결제 승인 요청용
 *
 * 프론트엔드 흐름:
 * 1. 토스 결제위젯 표시 → 사용자 결제 수단 선택
 * 2. 결제위젯에서 paymentKey, orderId, amount 반환
 * 3. 백엔드로 결제 승인 요청
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class PrepaymentRequestDto {

    @NotBlank(message = "결제 키는 필수입니다.")
    private String paymentKey;

    @NotBlank(message = "주문 ID는 필수입니다.")
    private String orderId;

    @NotNull(message = "결제 금액은 필수입니다.")
    @Positive(message = "결제 금액은 양수여야 합니다.")
    private Long amount;
}