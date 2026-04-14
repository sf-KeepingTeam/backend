package com.ssafy.keeping.testutil.fixture;

import com.ssafy.keeping.domain.store.constant.StoreStatus;
import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.user.owner.model.Owner;

import java.util.UUID;

public final class StoreFixtures {

    private StoreFixtures() {}

    public static Store activeStore(Owner owner, String storeName) {
        String uniq = UUID.randomUUID().toString().substring(0, 8);

        return Store.builder()
                .owner(owner)
                .taxIdNumber("123-45-" + uniq) // 유니크
                .storeName(storeName)
                .address("서울시 강남구 어딘가 " + uniq) // 유니크
                .category("CAFE")
                .imgUrl("https://example.com/store.png")
                .storeStatus(StoreStatus.ACTIVE)
                .build();
    }

    public static Store store(Owner owner, String storeName, StoreStatus status, String category) {
        String uniq = UUID.randomUUID().toString().substring(0, 8);

        return Store.builder()
                .owner(owner)
                .taxIdNumber("123-45-" + uniq)
                .storeName(storeName)
                .address("서울시 강남구 어딘가 " + uniq)
                .category(category)
                .imgUrl("https://example.com/store.png")
                .storeStatus(status)
                .build();
    }

    public static Store storeWithTaxAndAddress(
            Owner owner,
            String storeName,
            StoreStatus status,
            String category,
            String taxIdNumber,
            String address
    ) {
        return Store.builder()
                .owner(owner)
                .taxIdNumber(taxIdNumber)
                .storeName(storeName)
                .address(address)
                .category(category)
                .imgUrl("https://example.com/store.png")
                .storeStatus(status)
                .build();
    }
}
