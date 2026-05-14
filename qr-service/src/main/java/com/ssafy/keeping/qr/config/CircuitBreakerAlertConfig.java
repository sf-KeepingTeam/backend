package com.ssafy.keeping.qr.config;

import com.ssafy.keeping.qr.common.alert.AlertService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class CircuitBreakerAlertConfig {

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final AlertService alertService;

    @PostConstruct
    public void registerEventListeners() {
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(this::registerListener);
        circuitBreakerRegistry.getEventPublisher()
                .onEntryAdded(event -> registerListener(event.getAddedEntry()));
    }

    private void registerListener(CircuitBreaker circuitBreaker) {
        circuitBreaker.getEventPublisher()
                .onStateTransition(this::onStateTransition);
    }

    private void onStateTransition(CircuitBreakerOnStateTransitionEvent event) {
        if (event.getStateTransition() == CircuitBreaker.StateTransition.CLOSED_TO_OPEN
                || event.getStateTransition() == CircuitBreaker.StateTransition.HALF_OPEN_TO_OPEN) {
            log.warn("서킷브레이커 OPEN: {}", event.getCircuitBreakerName());
            alertService.notifyCircuitBreakerOpen(event.getCircuitBreakerName());
        }
    }
}
