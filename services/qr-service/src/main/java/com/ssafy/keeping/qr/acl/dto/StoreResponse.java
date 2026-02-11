package com.ssafy.keeping.qr.acl.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class StoreResponse {
    private Long storeId;
    private String storeName;
    private Long ownerId;
    private String taxIdNumber;
    private String address;
    private boolean isActive;
}
