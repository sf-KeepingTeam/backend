package com.ssafy.keeping.domain.payment.funds.service;

import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.user.customer.repository.CustomerRepository;
import com.ssafy.keeping.domain.menu.model.Menu;
import com.ssafy.keeping.domain.menu.repository.MenuRepository;
import com.ssafy.keeping.domain.payment.funds.dto.FundsResult;
import com.ssafy.keeping.domain.payment.intent.model.PaymentIntent;
import com.ssafy.keeping.domain.payment.intent.model.PaymentIntentItem;
import com.ssafy.keeping.domain.payment.intent.repository.PaymentIntentItemRepository;
import com.ssafy.keeping.domain.payment.transactions.constant.TransactionType;
import com.ssafy.keeping.domain.payment.transactions.model.Transaction;
import com.ssafy.keeping.domain.payment.transactions.model.TransactionItem;
import com.ssafy.keeping.domain.payment.transactions.repository.TransactionItemRepository;
import com.ssafy.keeping.domain.payment.transactions.repository.TransactionRepository;
import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.store.repository.StoreRepository;
import com.ssafy.keeping.domain.wallet.model.Wallet;
import com.ssafy.keeping.domain.wallet.model.WalletLotMove;
import com.ssafy.keeping.domain.wallet.model.WalletStoreLot;
import com.ssafy.keeping.domain.wallet.repository.WalletLotMoveRepository;
import com.ssafy.keeping.domain.wallet.repository.WalletRepository;
import com.ssafy.keeping.domain.wallet.repository.WalletStoreBalanceRepository;
import com.ssafy.keeping.domain.wallet.repository.WalletStoreLotRepository;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FundsService {

    private static class LotUse {
        private final Long lotId;
        private final Long used;

        private LotUse(Long lotId, Long used) {
            this.lotId = lotId;
            this.used = used;
        }

        public Long getLotId() { return lotId; }
        public Long getUsed()  { return used; }
    }

    // === Wallet 도메인 (MSA 전환 시 WalletService API 호출로 대체) ===
    private final WalletStoreBalanceRepository balanceRepository;
    private final WalletStoreLotRepository lotRepository;
    private final WalletLotMoveRepository lotMoveRepository;
    private final WalletRepository walletRepository;

    // === 외부 도메인 의존성 (MSA 전환 시 제거 가능 - PaymentIntent에 이미 스냅샷 저장) ===
    // User/Store/Menu: PaymentIntent 생성 시점에 이미 검증됨, 여기서는 불필요
    // 현재 미사용 상태 - 추후 제거 가능
    private final CustomerRepository customerRepository;
    private final StoreRepository storeRepository;
    private final MenuRepository menuRepository;

    // === Payment 도메인 내부 ===
    private final TransactionRepository txRepository;
    private final TransactionItemRepository txItemRepository;
    private final PaymentIntentItemRepository intentItemRepository;
    private final Clock clock;

    /**
     * 자금 캡처 실행
     * - REQUIRED로도 충분하지만, 승인 트랜잭션 내부에서만 실행되도록 MANDATORY 사용 권장
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public FundsResult capture(PaymentIntent intent) {
        if (intent.getAmount() == null || intent.getAmount() <= 0L) {
            throw new CustomException(ErrorCode.BAD_REQUEST);
        }

        Long walletId = intent.getWalletId(); // 포인트 사용이 발생하는 지갑
        Long storeId = intent.getStoreId(); // 포인트 사용이 발생하는 가맹점
        LocalDateTime now = LocalDateTime.now(clock);
        long amount = intent.getAmount(); // 포인트 사용 요청 금액

        // 정책 검사 (일일 한도/가맹점 정책 등) — 필요 시 구현
//        if (!isPolicyOk(intent, amount)) {
//            return FundsResult.policyViolation(); // 422 매핑
//        }

        // 가게별 잔액 원자적 차감 (영향행 1이면 성공)
        int affected = balanceRepository.decrementIfEnough(walletId, storeId, amount);
        if (affected != 1) {
            return FundsResult.insufficient(); // 잔액 불충분
        }

        // 로트 FIFO 차감 (만료 전 ACTIVE 로트만, 한 로트씩 조건부 차감)
        long remain = amount; // remain : 남은 결제 금액
        List<LotUse> consumed = new ArrayList<>();

        List<WalletStoreLot> lots = lotRepository.findSpendableLots(walletId, storeId, now);
        for (WalletStoreLot lot : lots) {
            if (remain <= 0L) break;

            long available = lot.getAmountRemaining() == null ? 0L : lot.getAmountRemaining();
            if (available <= 0L) continue;

            long use = Math.min(available, remain);
            int ok = lotRepository.decrementLotIfEnough(lot.getLotId(), use, now);
            if (ok == 1) {
                consumed.add(new LotUse(lot.getLotId(), use));
                remain -= use;
            }
        }

        if (remain > 0L) { // 이론 상 남은 결제금액이 > 0 인 경우는 있으면 안됨.... 잔액 부족 상태
            throw new CustomException(ErrorCode.FUNDS_INVARIANT_VIOLATION);
        }

        // 거래 내역 생성 (USE)
        Transaction tx = Transaction.builder()
                .walletId(walletId)
                .customerId(intent.getCustomerId())
                .storeId(storeId)
                .transactionType(TransactionType.USE)
                .amount(amount)
                .createdAt(now)
                .build();
        tx = txRepository.save(tx);

        // 품목 원장(스냅샷) 생성
        List<PaymentIntentItem> intentItems = intentItemRepository.findByIntent_IntentId(intent.getIntentId());
        List<TransactionItem> rows = new ArrayList<>();

        Long txStoreId = tx.getStoreId();
        if (storeId != null && !storeId.equals(txStoreId)) {
            throw new CustomException(ErrorCode.STORE_NOT_MATCH);
        }

        for (PaymentIntentItem it : intentItems) {
            TransactionItem row = TransactionItem.builder()
                    .transaction(tx)
                    .storeId(storeId)
                    .menuId(it.getMenuId())
                    .menuNameSnapshot(it.getMenuNameSnap())
                    .menuPriceSnapshot(it.getUnitPriceSnap())
                    .quantity(it.getQuantity())
                    .build();
            rows.add(row);
        }
        txItemRepository.saveAll(rows);

        // 로트 증감 기록 (USE ⇒ delta 음수)
        List<WalletLotMove> moves = new ArrayList<>();
        for (LotUse u : consumed) {
            WalletStoreLot lotRef = lotRepository.getReferenceById(u.getLotId());

            WalletLotMove mv = WalletLotMove.builder()
                    .transaction(tx)
                    .lot(lotRef)
                    .delta(-u.getUsed())
                    .build();
            moves.add(mv);
        }
        lotMoveRepository.saveAll(moves);

        return FundsResult.ok(tx.getTransactionId());
    }

}
