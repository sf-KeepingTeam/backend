package com.ssafy.keeping.qr.domain.intent.service;

import com.ssafy.keeping.qr.domain.intent.constant.PaymentStatus;
import com.ssafy.keeping.qr.domain.intent.model.PaymentIntent;
import com.ssafy.keeping.qr.domain.intent.repository.PaymentIntentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntentStatusUpdater {

    private final PaymentIntentRepository paymentIntentRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markUncertain(Long intentId, String reason) {
        PaymentIntent intent = paymentIntentRepository.findById(intentId).orElse(null);
        if (intent != null && intent.getStatus() == PaymentStatus.PENDING) {
            intent.setStatus(PaymentStatus.UNCERTAIN);
            paymentIntentRepository.save(intent);
            log.info("Intent UNCERTAIN 상태로 변경 (별도 트랜잭션): intentId={}, reason={}", intentId, reason);
        }
    }
}
