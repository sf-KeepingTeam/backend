package com.ssafy.keeping.domain.internal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FundsResponse {
    private boolean sufficient;
    private boolean policyOk;
    private Long transactionId;

    public static FundsResponse insufficient() {
        return FundsResponse.builder()
                .sufficient(false)
                .policyOk(true)
                .build();
    }

    public static FundsResponse policyViolation() {
        return FundsResponse.builder()
                .sufficient(true)
                .policyOk(false)
                .build();
    }

    public static FundsResponse ok(Long transactionId) {
        return FundsResponse.builder()
                .sufficient(true)
                .policyOk(true)
                .transactionId(transactionId)
                .build();
    }
}
