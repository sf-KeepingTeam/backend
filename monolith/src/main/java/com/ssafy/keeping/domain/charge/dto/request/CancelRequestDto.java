package com.ssafy.keeping.domain.charge.dto.request;

import lombok.*;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

/**
 * 결제 취소 요청 DTO
 * 토스페이먼츠 결제 취소 API용
 *
 * 취소 방식:
 * 1. paymentKey로 취소: 결제 키를 직접 전달
 * 2. transactionUniqueNo로 취소: 내부 거래번호로 조회 후 취소
 *
 * 둘 중 하나만 전달해도 됨 (paymentKey 우선)
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class CancelRequestDto {

    /**
     * 토스 결제 키 (결제 승인 시 받은 키)
     * paymentKey 또는 transactionUniqueNo 중 하나 필수
     */
    private String paymentKey;

    /**
     * 내부 거래 고유번호 (하위 호환성용)
     * Transaction.transactionUniqueNo와 매핑
     */
    private String transactionUniqueNo;

    /**
     * 취소 사유 (필수)
     */
    @NotBlank(message = "취소 사유는 필수입니다.")
    private String cancelReason;

    /**
     * 취소 금액 (부분취소 시 사용, null이면 전액 취소)
     */
    @Positive(message = "취소 금액은 양수여야 합니다.")
    private Long cancelAmount;
}