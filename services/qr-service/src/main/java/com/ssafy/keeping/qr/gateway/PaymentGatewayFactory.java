package com.ssafy.keeping.qr.gateway;

import com.ssafy.keeping.qr.common.exception.CustomException;
import com.ssafy.keeping.qr.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class PaymentGatewayFactory {

    private final Map<PaymentProvider, PaymentGateway> gateways;
    private final PaymentProvider defaultProvider;

    public PaymentGatewayFactory(
            List<PaymentGateway> gatewayList,
            @Value("${payment.provider:toss}") String defaultProviderName
    ) {
        this.gateways = gatewayList.stream()
                .collect(Collectors.toMap(
                        PaymentGateway::getProviderType,
                        Function.identity()
                ));

        this.defaultProvider = PaymentProvider.valueOf(defaultProviderName.toUpperCase());

        log.info("[PaymentGatewayFactory] 초기화 완료 - 등록된 게이트웨이: {}, 기본 제공자: {}",
                gateways.keySet(), defaultProvider);
    }

    public PaymentGateway getDefaultGateway() {
        return getGateway(defaultProvider);
    }

    public PaymentGateway getGateway(PaymentProvider provider) {
        PaymentGateway gateway = gateways.get(provider);

        if (gateway == null) {
            log.error("[PaymentGatewayFactory] 지원하지 않는 결제 제공자: {}", provider);
            throw new CustomException(ErrorCode.UNSUPPORTED_PAYMENT_PROVIDER);
        }

        return gateway;
    }

    public boolean isSupported(PaymentProvider provider) {
        return gateways.containsKey(provider);
    }

    public List<PaymentProvider> getAvailableProviders() {
        return List.copyOf(gateways.keySet());
    }
}
