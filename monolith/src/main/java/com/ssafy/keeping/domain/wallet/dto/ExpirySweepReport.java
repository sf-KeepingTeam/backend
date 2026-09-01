package com.ssafy.keeping.domain.wallet.dto;

public record ExpirySweepReport(
    long settledGroups,
    long settledLots,
    long settledAmount,
    long skippedLocked,
    long remainingBacklog) {}
