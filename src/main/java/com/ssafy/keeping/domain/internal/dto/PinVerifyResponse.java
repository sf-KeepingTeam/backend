package com.ssafy.keeping.domain.internal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PinVerifyResponse {
    private boolean verified;
    private Long customerId;

    public static PinVerifyResponse valid(Long customerId) {
        return PinVerifyResponse.builder()
                .verified(true)
                .customerId(customerId)
                .build();
    }

    public static PinVerifyResponse invalid(Long customerId) {
        return PinVerifyResponse.builder()
                .verified(false)
                .customerId(customerId)
                .build();
    }
}
