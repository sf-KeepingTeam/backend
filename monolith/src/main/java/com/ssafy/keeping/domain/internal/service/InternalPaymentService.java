package com.ssafy.keeping.domain.internal.service;

import com.ssafy.keeping.domain.idempotency.constant.IdemStatus;
import com.ssafy.keeping.domain.idempotency.model.IdempotencyKey;
import com.ssafy.keeping.domain.idempotency.repository.IdempotencyKeyRepository;
import com.ssafy.keeping.domain.internal.dto.FundsResponse;
import com.ssafy.keeping.domain.internal.dto.PaymentCheckResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 결제 상태 확인 서비스
 * - 멱등성 키를 통해 결제 존재 여부 확인
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InternalPaymentService {

    private final IdempotencyKeyRepository idempotencyKeyRepository;

    @Qualifier("canonicalObjectMapper")
    private final ObjectMapper canonicalObjectMapper;

    /**
     * 멱등성 키로 결제 존재 여부 확인
     * - 완료된 결제가 있으면 트랜잭션 정보 반환
     * - 없으면 exists=false 반환
     */
    @Transactional(readOnly = true)
    public PaymentCheckResponse checkPayment(String idempotencyKeyStr) {
        UUID keyUuid;
        try {
            keyUuid = UUID.fromString(idempotencyKeyStr);
        } catch (IllegalArgumentException e) {
            log.warn("잘못된 멱등성 키 형식: {}", idempotencyKeyStr);
            return PaymentCheckResponse.builder()
                    .exists(false)
                    .build();
        }

        return idempotencyKeyRepository.findByKeyUuid(keyUuid)
                .filter(key -> key.getStatus() == IdemStatus.DONE)
                .map(this::buildResponseFromIdempotencyKey)
                .orElse(PaymentCheckResponse.builder()
                        .exists(false)
                        .build());
    }

    private PaymentCheckResponse buildResponseFromIdempotencyKey(IdempotencyKey key) {
        try {
            if (key.getResponseJson() == null) {
                return PaymentCheckResponse.builder()
                        .exists(false)
                        .build();
            }

            FundsResponse snapshot = canonicalObjectMapper.treeToValue(
                    key.getResponseJson(), FundsResponse.class);

            // 결제 성공인 경우만 exists=true
            if (snapshot.isSufficient() && snapshot.isPolicyOk() && snapshot.getTransactionId() != null) {
                return PaymentCheckResponse.builder()
                        .exists(true)
                        .transactionId(snapshot.getTransactionId())
                        .build();
            }

            return PaymentCheckResponse.builder()
                    .exists(false)
                    .build();

        } catch (Exception e) {
            log.error("멱등성 키 응답 파싱 실패: keyUuid={}, error={}", key.getKeyUuid(), e.getMessage());
            return PaymentCheckResponse.builder()
                    .exists(false)
                    .build();
        }
    }
}
