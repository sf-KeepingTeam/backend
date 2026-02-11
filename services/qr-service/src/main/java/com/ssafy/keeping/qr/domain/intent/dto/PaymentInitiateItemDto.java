package com.ssafy.keeping.qr.domain.intent.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentInitiateItemDto {

    @NotNull(message = "menuId는 필수입니다")
    private Long menuId;

    @Positive(message = "수량은 1 이상이어야 합니다")
    private int quantity;
}
