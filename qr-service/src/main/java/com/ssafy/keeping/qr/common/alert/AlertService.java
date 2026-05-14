package com.ssafy.keeping.qr.common.alert;

import com.ssafy.keeping.qr.domain.intent.model.PaymentIntent;

public interface AlertService {

    void notifyRecoveryFailed(PaymentIntent intent, String reason);

    void notifyCircuitBreakerOpen(String clientName);

    void notifyRecoveryWarning(PaymentIntent intent, int retryCount);
}
