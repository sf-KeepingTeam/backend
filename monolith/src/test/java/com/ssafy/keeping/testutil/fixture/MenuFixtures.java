package com.ssafy.keeping.testutil.fixture;

import com.ssafy.keeping.domain.menu.model.Menu;
import com.ssafy.keeping.domain.menucategory.model.MenuCategory;
import com.ssafy.keeping.domain.store.model.Store;

public final class MenuFixtures {

    private MenuFixtures() {}

    public static Menu menu(Store store, MenuCategory category, String menuName, int price) {
        return Menu.builder()
                .store(store)
                .category(category)
                .menuName(menuName)
                .price(price)
                .description("")
                .displayOrder(0)
                .imgUrl("https://example.com/default.jpg")
                .build();
    }

    public static Menu menu(Store store, MenuCategory category, String menuName, int price, int displayOrder) {
        return Menu.builder()
                .store(store)
                .category(category)
                .menuName(menuName)
                .price(price)
                .description("")
                .displayOrder(displayOrder)
                .imgUrl("https://example.com/default.jpg")
                .build();
    }

    public static Menu menuWithDescription(Store store, MenuCategory category, String menuName, int price, String description) {
        return Menu.builder()
                .store(store)
                .category(category)
                .menuName(menuName)
                .price(price)
                .description(description)
                .displayOrder(0)
                .imgUrl("https://example.com/default.jpg")
                .build();
    }

    public static Menu inactiveMenu(Store store, MenuCategory category, String menuName, int price) {
        return inactiveMenu(store, category, menuName, price, 0);
    }

    public static Menu inactiveMenu(Store store, MenuCategory category, String menuName, int price, int displayOrder) {
        return Menu.builder()
                .store(store)
                .category(category)
                .menuName(menuName)
                .price(price)
                .description("")
                .displayOrder(displayOrder)
                .imgUrl("https://example.com/default.jpg")
                .active(false)
                .build();
    }

    public static Menu soldOutMenu(Store store, MenuCategory category, String menuName, int price) {
        return soldOutMenu(store, category, menuName, price, 0);
    }

    public static Menu soldOutMenu(Store store, MenuCategory category, String menuName, int price, int displayOrder) {
        return Menu.builder()
                .store(store)
                .category(category)
                .menuName(menuName)
                .price(price)
                .description("")
                .displayOrder(displayOrder)
                .imgUrl("https://example.com/default.jpg")
                .soldOut(true)
                .build();
    }
}