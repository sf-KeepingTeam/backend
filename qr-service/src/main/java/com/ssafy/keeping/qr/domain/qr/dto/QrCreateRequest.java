package com.ssafy.keeping.qr.domain.qr.dto;

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

    private Long bindStoreId;
}
