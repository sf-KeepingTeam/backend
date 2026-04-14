package com.ssafy.keeping.qr.domain.intent.dto;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentIntentItemView {

    private Long menuId;
    private String name;
    private Long unitPrice;
    private int quantity;
    private Long lineTotal;
}
