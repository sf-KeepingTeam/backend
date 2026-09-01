package com.ssafy.keeping.domain.wallet.dto;

import java.time.LocalDateTime;
import java.util.List;

public record ReconcileReport(
    LocalDateTime startedAt,
    LocalDateTime finishedAt,
    long scannedPairs,
    long mismatchCount,
    long balanceOverLotSum,
    long balanceUnderLotSum,
    List<MismatchRow> samples) {}
