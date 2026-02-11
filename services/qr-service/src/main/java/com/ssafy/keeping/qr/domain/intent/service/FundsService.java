package com.ssafy.keeping.qr.domain.intent.service;

import com.ssafy.keeping.qr.acl.WalletClient;
import com.ssafy.keeping.qr.acl.dto.FundsCaptureRequest;
import com.ssafy.keeping.qr.acl.dto.FundsResponse;
import com.ssafy.keeping.qr.domain.intent.model.PaymentIntent;
import com.ssafy.keeping.qr.domain.intent.model.PaymentIntentItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 자금 서비스 - ACL을 통해 모놀리스의 Wallet 서비스 호출
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FundsService {

    private final WalletClient walletClient;

    /**
     * 자금 캡처 (잔액 차감 + 거래 내역 생성)
     */
    public FundsResult capture(PaymentIntent intent, List<PaymentIntentItem> items) {
        try {
            List<FundsCaptureRequest.ItemSnapshot> itemSnapshots = items.stream()
                    .map(item -> FundsCaptureRequest.ItemSnapshot.builder()
                            .menuId(item.getMenuId())
                            .menuName(item.getMenuNameSnap())
                            .unitPrice(item.getUnitPriceSnap())
                            .quantity(item.getQuantity())
                            .build())
                    .collect(Collectors.toList());

            FundsCaptureRequest request = FundsCaptureRequest.builder()
                    .walletId(intent.getWalletId())
                    .storeId(intent.getStoreId())
                    .customerId(intent.getCustomerId())
                    .amount(intent.getAmount())
                    .items(itemSnapshots)
                    .build();

            FundsResponse response = walletClient.capture(request);

            if (response == null) {
                return FundsResult.failed();
            }

            return new FundsResult(
                    response.isSufficient(),
                    response.isPolicyOk(),
                    response.getTransactionId()
            );

        } catch (Exception e) {
            log.error("자금 캡처 실패: intentId={}, error={}", intent.getIntentId(), e.getMessage());
            return FundsResult.failed();
        }
    }

    /**
     * 자금 복원 (결제 취소 시)
     */
    public void restore(Long walletId, Long storeId, Long amount) {
        walletClient.restore(walletId, storeId, amount);
    }

    public static class FundsResult {
        private final boolean sufficient;
        private final boolean policyOk;
        private final Long transactionId;

        public FundsResult(boolean sufficient, boolean policyOk, Long transactionId) {
            this.sufficient = sufficient;
            this.policyOk = policyOk;
            this.transactionId = transactionId;
        }

        public static FundsResult insufficient() {
            return new FundsResult(false, true, null);
        }

        public static FundsResult policyViolation() {
            return new FundsResult(true, false, null);
        }

        public static FundsResult failed() {
            return new FundsResult(false, false, null);
        }

        public static FundsResult ok(Long txId) {
            return new FundsResult(true, true, txId);
        }

        public boolean isSufficient() {
            return sufficient;
        }

        public boolean isPolicyOk() {
            return policyOk;
        }

        public Long getTransactionId() {
            return transactionId;
        }
    }
}
