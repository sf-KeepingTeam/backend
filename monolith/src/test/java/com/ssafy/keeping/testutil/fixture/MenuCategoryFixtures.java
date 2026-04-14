package com.ssafy.keeping.testutil.fixture;

import com.ssafy.keeping.domain.menucategory.model.MenuCategory;
import com.ssafy.keeping.domain.store.model.Store;

public final class MenuCategoryFixtures {

    private MenuCategoryFixtures() {}

    public static MenuCategory category(Store store, String categoryName) {
        return MenuCategory.builder()
                .store(store)
                .categoryName(categoryName)
                .displayOrder(0)
                .build();
    }

    public static MenuCategory category(Store store, String categoryName, int displayOrder) {
        return MenuCategory.builder()
                .store(store)
                .categoryName(categoryName)
                .displayOrder(displayOrder)
                .build();
    }

    public static MenuCategory subCategory(Store store, MenuCategory parent, String categoryName) {
        return subCategory(store, parent, categoryName, 0);
    }

    public static MenuCategory subCategory(Store store, MenuCategory parent, String categoryName, int displayOrder) {
        return MenuCategory.builder()
                .store(store)
                .parent(parent)
                .categoryName(categoryName)
                .displayOrder(displayOrder)
                .build();
    }
}