package com.ssafy.keeping.domain.charge.dto.response;

import com.ssafy.keeping.domain.charge.model.ChargeBonus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChargeBonusResponseDto {

    private Long chargeBonusId;
    private Long storeId;
    private String storeName;
    private Long chargeAmount;
    private Integer bonusPercentage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ChargeBonusResponseDto from(ChargeBonus chargeBonus, Long storeId, String storeName) {
        return ChargeBonusResponseDto.builder()
                .chargeBonusId(chargeBonus.getChargeBonusId())
                .storeId(storeId)
                .storeName(storeName)
                .chargeAmount(chargeBonus.getChargeAmount())
                .bonusPercentage(chargeBonus.getBonusPercentage())
                .createdAt(chargeBonus.getCreatedAt())
                .updatedAt(chargeBonus.getUpdatedAt())
                .build();
    }
}