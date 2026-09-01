package com.ssafy.keeping.domain.internal.dto;

import com.ssafy.keeping.domain.menu.model.Menu;
import java.time.ZoneId;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MenuResponse {
  private Long menuId;
  private Long storeId;
  private String menuName;
  private Integer price;
  private boolean active;
  private boolean soldOut;
  private long version;

  public static MenuResponse from(Menu menu) {
    long ver =
        menu.getUpdatedAt() != null
            ? menu.getUpdatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            : 0L;
    return MenuResponse.builder()
        .menuId(menu.getMenuId())
        .storeId(menu.getStore() != null ? menu.getStore().getStoreId() : null)
        .menuName(menu.getMenuName())
        .price(menu.getPrice())
        .active(menu.isActive())
        .soldOut(menu.isSoldOut())
        .version(ver)
        .build();
  }
}
