package com.ssafy.keeping.qr.saga.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 자금 캡처/복원을 위한 Saga 페이로드
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FundsCapturePayload {

    private String intentPublicId;
    private Long walletId;
    private Long storeId;
    private Long amount;
    private List<FundsCaptureItem> items;

    @Getter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FundsCaptureItem {
        private Long menuId;
        private String menuName;
        private Integer unitPrice;
        private Integer quantity;
    }
}
