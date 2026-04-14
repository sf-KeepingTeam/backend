package com.ssafy.keeping.domain.wallet.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class PointShareRequestDto {
    @NotNull
    private Long individualWalletId;
    @NotNull
    private Long groupWalletId;
    @NotNull
    private Long shareAmount;
}
