package com.ssafy.keeping.testutil.fixture;

import com.ssafy.keeping.domain.payment.transactions.constant.TransactionType;
import com.ssafy.keeping.domain.payment.transactions.model.Transaction;
import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.wallet.model.Wallet;

import java.time.LocalDateTime;
import java.util.UUID;

public final class TransactionFixtures {

    private TransactionFixtures() {}

    public static Transaction charge(Wallet wallet, Customer customer, Store store, Long amount) {
        return Transaction.builder()
                .wallet(wallet)
                .customer(customer)
                .store(store)
                .transactionType(TransactionType.CHARGE)
                .amount(amount)
                .transactionUniqueNo(UUID.randomUUID().toString())
                .build();
    }

    public static Transaction charge(Wallet wallet, Customer customer, Store store, Long amount, LocalDateTime createdAt) {
        return Transaction.builder()
                .wallet(wallet)
                .customer(customer)
                .store(store)
                .transactionType(TransactionType.CHARGE)
                .amount(amount)
                .transactionUniqueNo(UUID.randomUUID().toString())
                .createdAt(createdAt)
                .build();
    }

    public static Transaction use(Wallet wallet, Customer customer, Store store, Long amount) {
        return Transaction.builder()
                .wallet(wallet)
                .customer(customer)
                .store(store)
                .transactionType(TransactionType.USE)
                .amount(amount)
                .transactionUniqueNo(UUID.randomUUID().toString())
                .build();
    }

    public static Transaction use(Wallet wallet, Customer customer, Store store, Long amount, LocalDateTime createdAt) {
        return Transaction.builder()
                .wallet(wallet)
                .customer(customer)
                .store(store)
                .transactionType(TransactionType.USE)
                .amount(amount)
                .transactionUniqueNo(UUID.randomUUID().toString())
                .createdAt(createdAt)
                .build();
    }

    public static Transaction transaction(Wallet wallet, Customer customer, Store store,
                                          TransactionType type, Long amount, LocalDateTime createdAt) {
        return Transaction.builder()
                .wallet(wallet)
                .customer(customer)
                .store(store)
                .transactionType(type)
                .amount(amount)
                .transactionUniqueNo(UUID.randomUUID().toString())
                .createdAt(createdAt)
                .build();
    }
}