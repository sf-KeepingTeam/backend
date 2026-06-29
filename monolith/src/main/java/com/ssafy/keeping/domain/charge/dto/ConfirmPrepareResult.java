package com.ssafy.keeping.domain.charge.dto;

import com.ssafy.keeping.domain.charge.dto.response.PrepaymentResponseDto;
import com.ssafy.keeping.domain.idempotency.model.IdempotentResult;
import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.wallet.model.Wallet;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * Phase A (prepareConfirm) 결과.
 * replayResponse != null 이면 이미 처리된 요청 (COMPLETED) — 오케스트레이터에서 즉시 반환.
 */
@Getter
@AllArgsConstructor
@Builder
public class ConfirmPrepareResult {

    private final Long reservationId;
    private final String orderId;
    private final String paymentKey;
    private final Long amount;
    private final Store store;
    private final Wallet wallet;
    private final IdempotentResult<PrepaymentResponseDto> replayResponse;

    public boolean isReplay() {
        return replayResponse != null;
    }
}
