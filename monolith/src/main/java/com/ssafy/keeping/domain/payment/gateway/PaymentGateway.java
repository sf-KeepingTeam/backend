package com.ssafy.keeping.domain.payment.gateway;

import com.ssafy.keeping.domain.payment.gateway.dto.CancelRequest;
import com.ssafy.keeping.domain.payment.gateway.dto.CancelResult;
import com.ssafy.keeping.domain.payment.gateway.dto.PaymentRequest;
import com.ssafy.keeping.domain.payment.gateway.dto.PaymentResult;

/**
 * 결제 게이트웨이 인터페이스
 * 모든 결제 제공자(토스, 카카오, 네이버 등)는 이 인터페이스를 구현
 *
 * Strategy Pattern 적용:
 * - 새로운 결제 제공자 추가 시 이 인터페이스만 구현하면 됨
 * - 비즈니스 로직(PrepaymentService 등) 수정 불필요
 */
public interface PaymentGateway {

    /**
     * 결제 승인 처리
     *
     * @param request 결제 요청 정보 (paymentKey, orderId, amount 등)
     * @return 결제 결과
     */
    PaymentResult processPayment(PaymentRequest request);

    /**
     * 결제 취소 처리
     *
     * @param request 취소 요청 정보 (paymentKey, cancelReason 등)
     * @return 취소 결과
     */
    CancelResult cancelPayment(CancelRequest request);

    /**
     * 결제 제공자 타입 반환
     *
     * @return 결제 제공자 enum
     */
    PaymentProvider getProviderType();

    /**
     * 해당 결제 제공자 지원 여부 확인
     *
     * @param provider 확인할 결제 제공자
     * @return 지원 여부
     */
    default boolean supports(PaymentProvider provider) {
        return getProviderType() == provider;
    }
}
