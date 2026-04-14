package com.ssafy.keeping.domain.charge.dto.response;

import com.ssafy.keeping.domain.charge.model.ChargeBonus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChargeBonusListResponseDto {

    private Long chargeBonusId;
    private Long chargeAmount;
    private Integer bonusPercentage;

    public static ChargeBonusListResponseDto from(ChargeBonus chargeBonus) {
        return ChargeBonusListResponseDto.builder()
                .chargeBonusId(chargeBonus.getChargeBonusId())
                .chargeAmount(chargeBonus.getChargeAmount())
                .bonusPercentage(chargeBonus.getBonusPercentage())
                .build();
    }
}