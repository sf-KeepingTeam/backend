package com.ssafy.keeping.qr.domain.intent.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Redis {@code qrflow:intent:{tokenId}} 에 저장하는 JSON 캐시 구조.
 *
 * <p>AFTER_COMMIT 리스너가 initiate 커밋 직후 이 값을 JSON 으로 직렬화해 저장한다.
 * poll / immediate 경로에서 이 값으로 응답을 즉시 구성하므로 DB 왕복이 발생하지 않는다.
 * (수정 전: poll 해소 1건당 DB 2왕복. 수정 후: 0왕복)
 */
public record IntentArrivalCacheValue(
    @JsonProperty("intentPublicId") String intentPublicId,
    @JsonProperty("customerId") Long customerId,
    @JsonProperty("amount") Long amount,
    @JsonProperty("storeName") String storeName,
    @JsonProperty("items") List<CacheItem> items
) {

    /** Redis 캐시용 item 레코드. */
    public record CacheItem(
        @JsonProperty("menuId") Long menuId,
        @JsonProperty("name") String name,
        @JsonProperty("unitPrice") Long unitPrice,
        @JsonProperty("quantity") int quantity,
        @JsonProperty("lineTotal") long lineTotal
    ) {}

    public UUID intentUuid() {
        return UUID.fromString(intentPublicId);
    }

    /** {@link PaymentIntentItemView} 리스트로 변환. */
    public List<PaymentIntentItemView> toItemViews() {
        if (items == null) return Collections.emptyList();
        return items.stream()
            .map(it -> PaymentIntentItemView.builder()
                .menuId(it.menuId())
                .name(it.name())
                .unitPrice(it.unitPrice())
                .quantity(it.quantity())
                .lineTotal(it.lineTotal())
                .build())
            .toList();
    }

    /** {@link PaymentIntentItemView} 리스트로부터 캐시 값 생성. */
    public static IntentArrivalCacheValue of(
            UUID intentPublicId, Long customerId, Long amount, String storeName,
            List<PaymentIntentItemView> itemViews) {
        List<CacheItem> cacheItems = (itemViews == null) ? Collections.emptyList() :
            itemViews.stream()
                .map(it -> new CacheItem(
                    it.getMenuId(), it.getName(), it.getUnitPrice(),
                    it.getQuantity(), it.getLineTotal()))
                .toList();
        return new IntentArrivalCacheValue(
            intentPublicId.toString(), customerId, amount, storeName, cacheItems);
    }
}
