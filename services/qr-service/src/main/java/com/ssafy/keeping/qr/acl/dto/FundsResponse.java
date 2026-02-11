package com.ssafy.keeping.qr.acl.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class FundsResponse {
    private boolean sufficient;
    private boolean policyOk;
    private Long transactionId;
}
