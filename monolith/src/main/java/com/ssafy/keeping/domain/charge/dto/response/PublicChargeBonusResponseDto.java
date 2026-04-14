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
public class PublicChargeBonusResponseDto {

    private Long chargeAmount;
    private Integer bonusPercentage;
    private Long expectedTotalPoints;

    public static PublicChargeBonusResponseDto from(ChargeBonus chargeBonus) {
        Long bonusAmount = chargeBonus.getChargeAmount() * chargeBonus.getBonusPercentage() / 100;
        Long totalPoints = chargeBonus.getChargeAmount() + bonusAmount;

        return PublicChargeBonusResponseDto.builder()
                .chargeAmount(chargeBonus.getChargeAmount())
                .bonusPercentage(chargeBonus.getBonusPercentage())
                .expectedTotalPoints(totalPoints)
                .build();
    }
}