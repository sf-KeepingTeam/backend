package com.ssafy.keeping.domain.charge.dto.response;

import com.ssafy.keeping.domain.charge.model.ChargeBonus;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

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

  public static ChargeBonusResponseDto from(ChargeBonus chargeBonus) {
    return ChargeBonusResponseDto.builder()
        .chargeBonusId(chargeBonus.getChargeBonusId())
        .storeId(chargeBonus.getStore().getStoreId())
        .storeName(chargeBonus.getStore().getStoreName())
        .chargeAmount(chargeBonus.getChargeAmount())
        .bonusPercentage(chargeBonus.getBonusPercentage())
        .createdAt(chargeBonus.getCreatedAt())
        .updatedAt(chargeBonus.getUpdatedAt())
        .build();
  }
}
