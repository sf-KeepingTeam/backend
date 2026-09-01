package com.ssafy.keeping.domain.internal.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FundsCaptureRequest {
  private Long walletId;
  private Long storeId;
  private Long customerId;
  private Long amount;
  private List<ItemSnapshot> items;

  @Getter
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class ItemSnapshot {
    private Long menuId;
    private String menuName;
    private Long unitPrice;
    private Integer quantity;
  }
}
