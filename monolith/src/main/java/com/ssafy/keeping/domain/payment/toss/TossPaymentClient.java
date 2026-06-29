package com.ssafy.keeping.domain.payment.toss;

import com.ssafy.keeping.domain.payment.toss.dto.TossCancelRequest;
import com.ssafy.keeping.domain.payment.toss.dto.TossCancelResponse;
import com.ssafy.keeping.domain.payment.toss.dto.TossPaymentConfirmRequest;
import com.ssafy.keeping.domain.payment.toss.dto.TossPaymentConfirmResponse;

/**
 * 토스페이먼츠 클라이언트 추상화.
 * 구현체는 payment.toss.mode 설정으로 선택된다.
 *  - stub (기본): StubTossPaymentClient — 실제 호출 없이 성공 반환 (로컬/테스트)
 *  - real        : RealTossPaymentClient — 실제 토스 API 호출 (test_sk)
 */
public interface TossPaymentClient {

    TossPaymentConfirmResponse confirmPayment(TossPaymentConfirmRequest request);

    TossCancelResponse cancelPayment(String paymentKey, TossCancelRequest request);
}
