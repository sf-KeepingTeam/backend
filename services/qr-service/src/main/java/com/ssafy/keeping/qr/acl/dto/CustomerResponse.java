package com.ssafy.keeping.qr.acl.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class CustomerResponse {
    private Long customerId;
    private String name;
    private String email;
    private String phone;
}
