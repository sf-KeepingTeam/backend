package com.ssafy.keeping.domain.payment.gateway;

import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 결제 게이트웨이 팩토리
 * 설정에 따라 적절한 결제 제공자를 반환
 *
 * 사용법:
 * 1. 기본 제공자 사용: paymentGatewayFactory.getDefaultGateway()
 * 2. 특정 제공자 사용: paymentGatewayFactory.getGateway(PaymentProvider.TOSS)
 */
@Slf4j
@Component
public class PaymentGatewayFactory {

    private final Map<PaymentProvider, PaymentGateway> gateways;
    private final PaymentProvider defaultProvider;

    public PaymentGatewayFactory(
            List<PaymentGateway> gatewayList,
            @Value("${payment.provider:toss}") String defaultProviderName
    ) {
        // 모든 PaymentGateway 구현체를 Map으로 저장
        this.gateways = gatewayList.stream()
                .collect(Collectors.toMap(
                        PaymentGateway::getProviderType,
                        Function.identity()
                ));

        // 기본 제공자 설정
        this.defaultProvider = PaymentProvider.valueOf(defaultProviderName.toUpperCase());

        log.info("[PaymentGatewayFactory] 초기화 완료 - 등록된 게이트웨이: {}, 기본 제공자: {}",
                gateways.keySet(), defaultProvider);
    }

    /**
     * 기본 결제 게이트웨이 반환
     * application.yml의 payment.provider 설정 사용
     */
    public PaymentGateway getDefaultGateway() {
        return getGateway(defaultProvider);
    }

    /**
     * 특정 결제 제공자의 게이트웨이 반환
     *
     * @param provider 결제 제공자
     * @return 해당 제공자의 게이트웨이
     * @throws CustomException 지원하지 않는 제공자인 경우
     */
    public PaymentGateway getGateway(PaymentProvider provider) {
        PaymentGateway gateway = gateways.get(provider);

        if (gateway == null) {
            log.error("[PaymentGatewayFactory] 지원하지 않는 결제 제공자: {}", provider);
            throw new CustomException(ErrorCode.UNSUPPORTED_PAYMENT_PROVIDER);
        }

        return gateway;
    }

    /**
     * 특정 제공자 지원 여부 확인
     */
    public boolean isSupported(PaymentProvider provider) {
        return gateways.containsKey(provider);
    }

    /**
     * 현재 등록된 모든 제공자 목록 반환
     */
    public List<PaymentProvider> getAvailableProviders() {
        return List.copyOf(gateways.keySet());
    }
}
