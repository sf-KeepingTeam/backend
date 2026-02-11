package com.ssafy.keeping.qr.toss.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TossCancelRequest {

    private final String cancelReason;
    private final Long cancelAmount;
    private final RefundReceiveAccount refundReceiveAccount;

    @Getter
    @Builder
    public static class RefundReceiveAccount {
        private final String bank;
        private final String accountNumber;
        private final String holderName;
    }
}
