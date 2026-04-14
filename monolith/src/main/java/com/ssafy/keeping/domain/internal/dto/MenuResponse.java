package com.ssafy.keeping.domain.internal.dto;

import com.ssafy.keeping.domain.menu.model.Menu;
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

    public static MenuResponse from(Menu menu) {
        return MenuResponse.builder()
                .menuId(menu.getMenuId())
                .storeId(menu.getStore() != null ? menu.getStore().getStoreId() : null)
                .menuName(menu.getMenuName())
                .price(menu.getPrice())
                .active(menu.isActive())
                .soldOut(menu.isSoldOut())
                .build();
    }
}
