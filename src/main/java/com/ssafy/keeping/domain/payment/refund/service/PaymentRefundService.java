package com.ssafy.keeping.domain.payment.refund.service;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.domain.idempotency.constant.IdemActorType;
import com.ssafy.keeping.domain.idempotency.constant.IdemStatus;
import com.ssafy.keeping.domain.idempotency.dto.IdemBegin;
import com.ssafy.keeping.domain.idempotency.model.IdempotencyKey;
import com.ssafy.keeping.domain.idempotency.model.IdempotentResult;
import com.ssafy.keeping.domain.idempotency.repository.IdempotencyKeyRepository;
import com.ssafy.keeping.domain.idempotency.service.IdempotencyService;
import com.ssafy.keeping.domain.notification.entity.NotificationType;
import com.ssafy.keeping.domain.notification.service.NotificationService;
import com.ssafy.keeping.domain.payment.refund.dto.RefundResponse;
import com.ssafy.keeping.domain.payment.transactions.constant.TransactionType;
import com.ssafy.keeping.domain.payment.transactions.repository.TransactionRepository;
import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.store.repository.StoreRepository;
import com.ssafy.keeping.domain.wallet.model.Wallet;
import com.ssafy.keeping.domain.wallet.model.WalletLotMove;
import com.ssafy.keeping.domain.wallet.model.WalletStoreBalance;
import com.ssafy.keeping.domain.wallet.model.WalletStoreLot;
import com.ssafy.keeping.domain.wallet.repository.WalletLotMoveRepository;
import com.ssafy.keeping.domain.wallet.repository.WalletStoreBalanceRepository;
import com.ssafy.keeping.domain.wallet.repository.WalletStoreLotRepository;
import com.ssafy.keeping.domain.payment.transactions.model.Transaction;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 전액 결제 취소 서비스 (멱등 패턴: initiate 와 동일 흐름)
 * - 멱등 스코프: (actorType=MERCHANT, actorId=ownerId, path=/api/stores/{storeId}/transactions/{txId}/refund, key=Idempotency-Key)
 * - 상태 흐름:
 *   DONE                  → 저장된 응답 재생(200 OK)
 *   IN_PROGRESS(선점 중)   → 202 Accepted (+ Retry-After)
 *   신규                   → 본 처리 수행 → DONE 기록 후 201 Created
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRefundService {

    private final TransactionRepository transactionRepository;
    private final WalletStoreBalanceRepository walletStoreBalanceRepository;
    private final WalletLotMoveRepository walletLotMoveRepository;
    private final StoreRepository storeRepository;
    private final IdempotencyService idempotencyService;
    private final NotificationService notificationService;
    @Qualifier("canonicalObjectMapper")
    private final ObjectMapper canonicalObjectMapper;

    private final Clock clock;

    // 빈 바디 요청의 정규화 바디 → bodyHash(32바이트)
    private static final byte[] EMPTY_BODY_HASH = IdempotencyService.sha256("");

    /**
     * 전액 결제 취소
     * @param storeId        매장 ID
     * @param transactionId  원거래(=USE) 트랜잭션 ID
     * @param idempotencyKeyHeader 멱등 키
     * @param ownerId        점주 고유번호
     */
    @Transactional
    public IdempotentResult<RefundResponse> fullCancel(Long storeId,
                                                       Long transactionId,
                                                       String idempotencyKeyHeader,
                                                       Long ownerId
    ) {

        // 헤더/키 검증
        if (idempotencyKeyHeader == null || idempotencyKeyHeader.isBlank()) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        final UUID keyUuid;
        try {
            keyUuid = UUID.fromString(idempotencyKeyHeader);
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_INVALID);
        }

        // store 존재 확인
        boolean storeExists = storeRepository.existsById(storeId);
        if (!storeExists) throw new CustomException(ErrorCode.STORE_NOT_FOUND);

        // 소유 검증(점주-스토어)
        boolean owned = storeRepository.existsByStoreIdAndOwner_OwnerId(storeId, ownerId);
        if (!owned) throw new CustomException(ErrorCode.UNAUTHORIZED_ACCESS);

        // 원거래 조회 & 락
        Transaction original = transactionRepository.findByIdWithLock(transactionId)
                .orElseThrow(() -> new CustomException(ErrorCode.TRANSACTION_NOT_FOUND));

        // 스토어 일치/타입 검증
        if (!Objects.equals(original.getStore().getStoreId(), storeId)) {
            throw new CustomException(ErrorCode.UNAUTHORIZED_ACCESS); // "본인의 거래만 취소할 수 있습니다."
        }
        if (original.getTransactionType() != TransactionType.USE) { // 사용 상태만 취소할 수 있음
            throw new CustomException(ErrorCode.CANCEL_NOT_AVAILABLE);
        }

        // 이미 취소된 이력 존재하면 '우호적 재생' 반환
        if (transactionRepository.existsByRefTxIdAndType(original.getTransactionId(), TransactionType.CANCEL_USE)) {
            Transaction cancelTx = transactionRepository.findCancelByRef(original.getTransactionId(), TransactionType.CANCEL_USE)
                    .orElseThrow(() -> new CustomException(ErrorCode.INCONSISTENT_STATE));

            RefundResponse body = RefundResponse.builder()
                    .transactionId(original.getTransactionId())
                    .refundTransactionId(cancelTx.getTransactionId())
                    .amount(original.getAmount())
                    .refundedAt(cancelTx.getCreatedAt())
                    .build();
            return IdempotentResult.okReplay(body);
        }


        String path = "/api/stores/" + storeId + "/transactions/" + transactionId + "/refund"; // 스코프 정규화
        IdemBegin begin = idempotencyService.beginOrLoad(IdemActorType.MERCHANT, ownerId, "POST", path, keyUuid, EMPTY_BODY_HASH);

        IdempotencyKey slot = begin.getRow();

        // 본문 충돌 검사 - 여기서는 본문 없음...
        if (idempotencyService.isBodyConflict(slot, EMPTY_BODY_HASH)) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_BODY_CONFLICT);
        }

        // DONE 재생
        if (slot.getStatus() == IdemStatus.DONE) {

            RefundResponse replay;

            try {
                var node = slot.getResponseJson(); // JsonNode
                if (node != null && !node.isNull()) {
                    replay = canonicalObjectMapper.treeToValue(node, RefundResponse.class);
                } else {
                    throw new CustomException(ErrorCode.IDEMPOTENCY_REPLAY_UNAVAILABLE);
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new CustomException(ErrorCode.JSON_PARSE_ERROR);
            }

            return IdempotentResult.okReplay(replay);
        }

        // 다른 처리에서 IN_PROGRESS로 선점
        if (!begin.isCreated() && slot.getStatus() == IdemStatus.IN_PROGRESS) {
            return IdempotentResult.acceptedWithRetryAfterSeconds(2);
        }


        // 취소 원장 생성 (CANCEL_USE)
        LocalDateTime now = LocalDateTime.now(clock);
        Transaction cancelTx = Transaction.builder()
                .wallet(original.getWallet())
                .customer(original.getCustomer())
                .store(original.getStore())
                .transactionType(TransactionType.CANCEL_USE)
                .amount(original.getAmount())   // 전액
                .refTransaction(original)
                .build();
        transactionRepository.save(cancelTx);

        // 잔액 복원: wallet_store_balances += original.amount
        Wallet wallet = original.getWallet();
        Store store = original.getStore();
        WalletStoreBalance balance = walletStoreBalanceRepository.findByWalletAndStoreForUpdate(wallet, store)
                .orElseGet(() -> {
                    try {
                        return walletStoreBalanceRepository.save(WalletStoreBalance.builder()
                                .wallet(wallet)
                                .store(store)
                                .balance(0L)
                                .build());
                    } catch (DataIntegrityViolationException e) {
                        return walletStoreBalanceRepository.findByWalletAndStoreForUpdate(wallet, store)
                                .orElseThrow(() -> e);
                    }
                });
        balance.addBalance(original.getAmount());

        // LOT 복원: 원거래의 USE move(delta<0) 조회 → 각 lot.amount_remaining += (-delta)
        //    + 복원 move 기록(양수)
        List<WalletLotMove> usedMoves = walletLotMoveRepository.findAllByTransactionIdWithLotLock(original.getTransactionId());
        long sumRestore = 0L;
        for (WalletLotMove m : usedMoves) {
            long delta = m.getDelta();       // USE는 음수
            if (delta >= 0) continue;        // 방어
            long restore = -delta;           // 복원량(+)

            WalletStoreLot lot = m.getLot();
            // 상한 보호: (remaining + restore) <= total
            long newRemaining = lot.getAmountRemaining() + restore;
            if (newRemaining > lot.getAmountTotal()) {
                throw new CustomException(ErrorCode.FUNDS_INVARIANT_VIOLATION);
            }
            lot.setAmountRemaining(newRemaining);

            // 복원 move(+)
            WalletLotMove restoreMove = WalletLotMove.of(cancelTx, lot, restore);
            walletLotMoveRepository.save(restoreMove);

            sumRestore += restore;
        }

        // LOT 합계 == 결제금액 확인
        if (sumRestore != original.getAmount()) {
            throw new CustomException(ErrorCode.FUNDS_INVARIANT_VIOLATION);
        }

        // 손님에게 알림 전송
        try {
            String notificationContent = String.format("%s에서 %,d포인트 사용이 취소되었습니다.",
                    store.getStoreName(), original.getAmount());

            notificationService.sendToOwner(
                    original.getCustomer().getCustomerId(),
                    NotificationType.POINT_CANCELED,
                    notificationContent
            );

            log.info("결제 수락 알림 전송 완료 - 손님ID: {}, 결제 금액: {}, 사용 가게 ID: {}", original.getCustomer().getCustomerId(), original.getAmount(), store.getStoreId());
        } catch (Exception e) {
            log.warn("결제 수락 알림 전송 실패 - 손님ID: {}, 결제 금액: {}, 사용 가게 ID: {}", original.getCustomer().getCustomerId(), original.getAmount(), store.getStoreId());
        }

        // 멱등 complete
        RefundResponse res = RefundResponse.builder()
                .transactionId(original.getTransactionId())
                .refundTransactionId(cancelTx.getTransactionId())
                .amount(original.getAmount())
                .refundedAt(now)
                .build();

        idempotencyService.complete(slot, HttpStatus.CREATED.value(), res, null);

        return IdempotentResult.created(res);
    }

}