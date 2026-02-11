package com.ssafy.keeping.qr.domain.qr.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

    @Min(value = 5, message = "최소 TTL은 5초입니다")
    @Max(value = 300, message = "최대 TTL은 300초입니다")
    private Integer ttlSeconds;
}
