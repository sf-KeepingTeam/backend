package com.ssafy.keeping.domain.wallet.dto;

import com.ssafy.keeping.domain.payment.transactions.model.Transaction;

import java.time.LocalDateTime;

public record WalletStoreTransactionDetailDto(
        Long customerId,
        Long transactionId,
        String transactionType,    // CHARGE, USE, TRANSFER_IN, etc.
        Long amount,
        String transactionUniqueNo,
        LocalDateTime createdAt
) {
    public static WalletStoreTransactionDetailDto from(Transaction transaction) {
        return new WalletStoreTransactionDetailDto (
                transaction.getCustomerId(),
                transaction.getTransactionId(),
                transaction.getTransactionType().name(),
                transaction.getAmount(),
                transaction.getTransactionUniqueNo(),
                transaction.getCreatedAt()
        );
    }
}