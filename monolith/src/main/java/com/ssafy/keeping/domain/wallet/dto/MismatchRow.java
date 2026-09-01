package com.ssafy.keeping.domain.wallet.dto;

public record MismatchRow(
    Long walletId,
    Long storeId,
    long balance,
    long lotSum,
    long diff) {}
