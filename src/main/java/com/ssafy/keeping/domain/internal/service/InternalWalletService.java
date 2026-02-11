package com.ssafy.keeping.domain.internal.service;

import com.ssafy.keeping.domain.internal.dto.FundsCaptureRequest;
import com.ssafy.keeping.domain.internal.dto.FundsResponse;
import com.ssafy.keeping.domain.internal.dto.WalletBalanceResponse;
import com.ssafy.keeping.domain.menu.model.Menu;
import com.ssafy.keeping.domain.menu.repository.MenuRepository;
import com.ssafy.keeping.domain.payment.transactions.constant.TransactionType;
import com.ssafy.keeping.domain.payment.transactions.model.Transaction;
import com.ssafy.keeping.domain.payment.transactions.model.TransactionItem;
import com.ssafy.keeping.domain.payment.transactions.repository.TransactionItemRepository;
import com.ssafy.keeping.domain.payment.transactions.repository.TransactionRepository;
import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.store.repository.StoreRepository;
import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.user.customer.repository.CustomerRepository;
import com.ssafy.keeping.domain.wallet.model.Wallet;
import com.ssafy.keeping.domain.wallet.model.WalletStoreBalance;
import com.ssafy.keeping.domain.wallet.repository.WalletRepository;
import com.ssafy.keeping.domain.wallet.repository.WalletStoreBalanceRepository;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Slf4j
@Service
@RequiredArgsConstructor
public class InternalWalletService {

    private final WalletRepository walletRepository;
    private final WalletStoreBalanceRepository balanceRepository;
    private final StoreRepository storeRepository;
    private final CustomerRepository customerRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionItemRepository transactionItemRepository;
    private final MenuRepository menuRepository;

    /**
     * 지갑의 매장별 잔액 조회
     */
    @Transactional(readOnly = true)
    public WalletBalanceResponse getBalance(Long walletId, Long storeId) {
        WalletStoreBalance balance = balanceRepository.findByWalletIdAndStoreId(walletId, storeId)
                .orElse(null);

        BigDecimal balanceAmount = balance != null
                ? BigDecimal.valueOf(balance.getBalance())
                : BigDecimal.ZERO;

        return WalletBalanceResponse.builder()
                .walletId(walletId)
                .storeId(storeId)
                .balance(balanceAmount)
                .build();
    }

    /**
     * 자금 캡처 (결제 시 잔액 차감 + 거래 내역 생성)
     */
    @Transactional
    public FundsResponse capture(FundsCaptureRequest request) {
        Long walletId = request.getWalletId();
        Long storeId = request.getStoreId();
        Long customerId = request.getCustomerId();
        Long amount = request.getAmount();

        // 1. 엔티티 조회
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));

        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException(ErrorCode.STORE_NOT_FOUND));

        Customer customer = customerRepository.findByCustomerIdAndDeletedAtIsNull(customerId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 2. 잔액 조회 (행잠금)
        WalletStoreBalance balance = balanceRepository.lockByWalletIdAndStoreId(walletId, storeId)
                .orElse(null);

        if (balance == null || balance.getBalance() < amount) {
            log.warn("잔액 부족: walletId={}, storeId={}, required={}, actual={}",
                    walletId, storeId, amount, balance != null ? balance.getBalance() : 0);
            return FundsResponse.insufficient();
        }

        // 3. 잔액 차감
        balance.subtractBalance(amount);

        // 4. 거래 내역 생성
        Transaction transaction = transactionRepository.save(
                Transaction.builder()
                        .wallet(wallet)
                        .customer(customer)
                        .store(store)
                        .transactionType(TransactionType.USE)
                        .amount(amount)
                        .build()
        );

        // 5. 거래 항목 생성
        if (request.getItems() != null && !request.getItems().isEmpty()) {
            for (FundsCaptureRequest.ItemSnapshot item : request.getItems()) {
                Menu menu = null;
                if (item.getMenuId() != null) {
                    menu = menuRepository.findById(item.getMenuId()).orElse(null);
                }

                TransactionItem txItem = TransactionItem.of(
                        transaction,
                        storeId,
                        menu,
                        item.getMenuName(),
                        item.getUnitPrice(),
                        item.getQuantity()
                );
                transactionItemRepository.save(txItem);
            }
        }

        log.info("자금 캡처 완료: walletId={}, storeId={}, amount={}, txId={}",
                walletId, storeId, amount, transaction.getTransactionId());

        return FundsResponse.ok(transaction.getTransactionId());
    }

    /**
     * 잔액 복원 (결제 취소 시)
     */
    @Transactional
    public void restore(Long walletId, Long storeId, Long amount) {
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));

        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException(ErrorCode.STORE_NOT_FOUND));

        // 잔액 조회 (행잠금)
        WalletStoreBalance balance = balanceRepository.lockByWalletIdAndStoreId(walletId, storeId)
                .orElseGet(() -> balanceRepository.save(
                        WalletStoreBalance.builder()
                                .wallet(wallet)
                                .store(store)
                                .balance(0L)
                                .build()
                ));

        // 잔액 복원
        balance.addBalance(amount);

        log.info("잔액 복원 완료: walletId={}, storeId={}, amount={}", walletId, storeId, amount);
    }
}
