package com.ssafy.keeping.qrpayment.domain.qr.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QrCreateRequest {

    @NotNull(message = "walletId는 필수입니다")
    private Long walletId;

    @NotNull(message = "mode는 필수입니다")
    private String mode;

    private Long bindStoreId;

    private Integer ttlSeconds;
}
