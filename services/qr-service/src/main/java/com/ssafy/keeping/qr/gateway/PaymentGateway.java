package com.ssafy.keeping.qr.gateway;

import com.ssafy.keeping.qr.gateway.dto.CancelRequest;
import com.ssafy.keeping.qr.gateway.dto.CancelResult;
import com.ssafy.keeping.qr.gateway.dto.PaymentRequest;
import com.ssafy.keeping.qr.gateway.dto.PaymentResult;

/**
 * 결제 게이트웨이 인터페이스
 * Strategy Pattern 적용
 */
public interface PaymentGateway {

    PaymentResult processPayment(PaymentRequest request);

    CancelResult cancelPayment(CancelRequest request);

    PaymentProvider getProviderType();

    default boolean supports(PaymentProvider provider) {
        return getProviderType() == provider;
    }
}
