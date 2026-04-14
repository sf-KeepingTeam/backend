package com.ssafy.keeping.qr.domain.intent.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentInitiateRequest {

    @NotNull(message = "storeId는 필수입니다")
    private Long storeId;

    @NotEmpty(message = "주문 항목이 비어있습니다")
    @Valid
    private List<PaymentInitiateItemDto> orderItems;
}
