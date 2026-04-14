package com.ssafy.keeping.domain.charge.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ChargeBonusRequestDto {

    @NotNull(message = "충전 금액은 필수입니다.")
    @Min(value = 1000, message = "충전 금액은 최소 1,000원 이상이어야 합니다.")
    private Long chargeAmount;

    @NotNull(message = "보너스 퍼센트는 필수입니다.")
    @Min(value = 1, message = "보너스 퍼센트는 최소 1% 이상이어야 합니다.")
    @Max(value = 100, message = "보너스 퍼센트는 최대 100% 이하여야 합니다.")
    private Integer bonusPercentage;
}