package com.ssafy.keeping.qr.acl.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class PinVerifyRequest {
    private String pin;
}
