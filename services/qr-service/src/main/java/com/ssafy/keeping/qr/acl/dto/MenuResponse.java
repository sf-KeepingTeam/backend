package com.ssafy.keeping.qr.acl.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class MenuResponse {
    private Long menuId;
    private Long storeId;
    private String menuName;
    private Integer price;
    private boolean active;
    private boolean soldOut;
}
