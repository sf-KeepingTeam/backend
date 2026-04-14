package com.ssafy.keeping.qr.domain.intent.canonical;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonPropertyOrder({"storeId", "items"})
public class CanonicalInitiate {

    private Long storeId;

    @JsonPropertyOrder({"menuId", "quantity"})
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Item {
        private Long menuId;
        private int quantity;
    }

    private List<Item> items;
}
