package com.ssafy.keeping.domain.menu.controller;

import com.ssafy.keeping.domain.auth.enums.AuthProvider;
import com.ssafy.keeping.domain.menu.model.Menu;
import com.ssafy.keeping.domain.menu.repository.MenuRepository;
import com.ssafy.keeping.domain.menucategory.model.MenuCategory;
import com.ssafy.keeping.domain.menucategory.repository.MenuCategoryRepository;
import com.ssafy.keeping.domain.store.constant.StoreStatus;
import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.store.repository.StoreRepository;
import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.user.customer.repository.CustomerRepository;
import com.ssafy.keeping.domain.user.owner.model.Owner;
import com.ssafy.keeping.domain.user.owner.repository.OwnerRepository;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import com.ssafy.keeping.support.MySqlTestContainerConfig;
import com.ssafy.keeping.testutil.fixture.CustomerFixtures;
import com.ssafy.keeping.testutil.fixture.MenuCategoryFixtures;
import com.ssafy.keeping.testutil.fixture.MenuFixtures;
import com.ssafy.keeping.testutil.fixture.OwnerFixtures;
import com.ssafy.keeping.testutil.fixture.StoreFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static com.ssafy.keeping.testutil.security.TestAuth.customer;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("MenuController 통합 테스트")
class MenuControllerT extends MySqlTestContainerConfig {

    @Autowired MockMvc mockMvc;
    @Autowired MenuRepository menuRepository;
    @Autowired MenuCategoryRepository menuCategoryRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired OwnerRepository ownerRepository;
    @Autowired CustomerRepository customerRepository;

    Owner owner;
    Store store;
    Customer testCustomer;
    MenuCategory coffeeCategory;
    MenuCategory dessertCategory;

    @BeforeEach
    void setUp() {
        menuRepository.deleteAllInBatch();
        menuCategoryRepository.deleteAllInBatch();
        storeRepository.deleteAllInBatch();
        customerRepository.deleteAllInBatch();
        ownerRepository.deleteAllInBatch();

        owner = ownerRepository.save(OwnerFixtures.owner(AuthProvider.KAKAO, "kakao_" + UUID.randomUUID(), "테스트점주"));
        store = storeRepository.save(StoreFixtures.store(owner, "테스트카페", StoreStatus.ACTIVE, "CAFE"));
        testCustomer = customerRepository.save(CustomerFixtures.customer());

        coffeeCategory = menuCategoryRepository.save(MenuCategoryFixtures.category(store, "커피", 1));
        dessertCategory = menuCategoryRepository.save(MenuCategoryFixtures.category(store, "디저트", 2));
    }

    @Nested
    @DisplayName("GET /stores/{storeId}/menus - 가게 전체 메뉴 조회")
    class GetAllMenus {

        @Test
        @DisplayName("성공: 가게의 전체 메뉴 조회")
        void success() throws Exception {
            // 커피 카테고리 메뉴 2개
            Menu americano = menuRepository.save(MenuFixtures.menu(store, coffeeCategory, "아메리카노", 4500, 1));
            menuRepository.save(MenuFixtures.menu(store, coffeeCategory, "카페라떼", 5000, 2));
            // 디저트 카테고리 메뉴 1개
            menuRepository.save(MenuFixtures.menu(store, dessertCategory, "치즈케이크", 6500, 1));

            mockMvc.perform(get("/stores/{storeId}/menus", store.getStoreId())
                            .with(customer(testCustomer.getCustomerId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").value("메뉴가 전체 조회되었습니다"))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data", hasSize(3)))
                    // 아메리카노 검증
                    .andExpect(jsonPath("$.data[?(@.menuName == '아메리카노')].menuId").value(hasItem(americano.getMenuId().intValue())))
                    .andExpect(jsonPath("$.data[?(@.menuName == '아메리카노')].price").value(hasItem(4500)))
                    .andExpect(jsonPath("$.data[?(@.menuName == '아메리카노')].categoryName").value(hasItem("커피")))
                    // 카페라떼 검증
                    .andExpect(jsonPath("$.data[?(@.menuName == '카페라떼')].price").value(hasItem(5000)))
                    // 치즈케이크 검증
                    .andExpect(jsonPath("$.data[?(@.menuName == '치즈케이크')].price").value(hasItem(6500)))
                    .andExpect(jsonPath("$.data[?(@.menuName == '치즈케이크')].categoryName").value(hasItem("디저트")));
        }

        @Test
        @DisplayName("성공: 메뉴가 없는 경우 빈 배열 반환")
        void success_noMenus() throws Exception {
            // 메뉴 생성하지 않음
            mockMvc.perform(get("/stores/{storeId}/menus", store.getStoreId())
                            .with(customer(testCustomer.getCustomerId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data", hasSize(0)));
        }

        @Test
        @DisplayName("성공: active=false인 메뉴는 조회되지 않음")
        void success_inactiveMenuNotReturned() throws Exception {
            // active 메뉴 1개
            menuRepository.save(MenuFixtures.menu(store, coffeeCategory, "아메리카노", 4500, 1));
            // inactive 메뉴 1개 (조회되지 않아야 함)
            menuRepository.save(MenuFixtures.inactiveMenu(store, coffeeCategory, "비공개메뉴", 3000, 2));

            mockMvc.perform(get("/stores/{storeId}/menus", store.getStoreId())
                            .with(customer(testCustomer.getCustomerId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data", hasSize(1)))
                    .andExpect(jsonPath("$.data[0].menuName").value("아메리카노"))
                    .andExpect(jsonPath("$.data[?(@.menuName == '비공개메뉴')]").isEmpty());
        }

        @Test
        @DisplayName("성공: soldOut=true인 메뉴도 조회됨")
        void success_soldOutMenuReturned() throws Exception {
            menuRepository.save(MenuFixtures.menu(store, coffeeCategory, "아메리카노", 4500, 1));
            menuRepository.save(MenuFixtures.soldOutMenu(store, coffeeCategory, "품절메뉴", 5000, 2));

            mockMvc.perform(get("/stores/{storeId}/menus", store.getStoreId())
                            .with(customer(testCustomer.getCustomerId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data", hasSize(2)))
                    .andExpect(jsonPath("$.data[?(@.menuName == '품절메뉴')].soldOut").value(hasItem(true)))
                    .andExpect(jsonPath("$.data[?(@.menuName == '아메리카노')].soldOut").value(hasItem(false)));
        }

        @Test
        @DisplayName("실패: 존재하지 않는 가게 - STORE_NOT_FOUND")
        void fail_storeNotFound() throws Exception {
            mockMvc.perform(get("/stores/{storeId}/menus", 999999L)
                            .with(customer(testCustomer.getCustomerId())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.STORE_NOT_FOUND.getHttpStatus().value()))
                    .andExpect(jsonPath("$.message").value(ErrorCode.STORE_NOT_FOUND.getMessage()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    @Nested
    @DisplayName("GET /stores/{storeId}/menus/categories/{categoryId} - 카테고리별 메뉴 조회")
    class GetMenusByCategory {

        @Test
        @DisplayName("성공: 특정 카테고리의 메뉴만 조회")
        void success() throws Exception {
            // 커피 카테고리 메뉴 2개
            menuRepository.save(MenuFixtures.menu(store, coffeeCategory, "아메리카노", 4500, 1));
            menuRepository.save(MenuFixtures.menu(store, coffeeCategory, "카페라떼", 5000, 2));
            // 디저트 카테고리 메뉴 1개 (조회되지 않아야 함)
            menuRepository.save(MenuFixtures.menu(store, dessertCategory, "치즈케이크", 6500, 1));

            mockMvc.perform(get("/stores/{storeId}/menus/categories/{categoryId}",
                            store.getStoreId(), coffeeCategory.getCategoryId())
                            .with(customer(testCustomer.getCustomerId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").value("카테고리 별로 메뉴가 전체 조회되었습니다"))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data", hasSize(2)))
                    .andExpect(jsonPath("$.data[?(@.menuName == '아메리카노')]").isNotEmpty())
                    .andExpect(jsonPath("$.data[?(@.menuName == '카페라떼')]").isNotEmpty())
                    .andExpect(jsonPath("$.data[?(@.menuName == '치즈케이크')]").isEmpty());
        }

        @Test
        @DisplayName("성공: 해당 카테고리에 메뉴가 없으면 빈 배열 반환")
        void success_noMenusInCategory() throws Exception {
            // 커피 카테고리에만 메뉴 추가
            menuRepository.save(MenuFixtures.menu(store, coffeeCategory, "아메리카노", 4500));

            // 디저트 카테고리 조회 (메뉴 없음)
            mockMvc.perform(get("/stores/{storeId}/menus/categories/{categoryId}",
                            store.getStoreId(), dessertCategory.getCategoryId())
                            .with(customer(testCustomer.getCustomerId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data", hasSize(0)));
        }

        @Test
        @DisplayName("성공: active=false인 메뉴는 조회되지 않음")
        void success_inactiveMenuNotReturned() throws Exception {
            menuRepository.save(MenuFixtures.menu(store, coffeeCategory, "아메리카노", 4500, 1));
            menuRepository.save(MenuFixtures.inactiveMenu(store, coffeeCategory, "비공개메뉴", 3000, 2));

            mockMvc.perform(get("/stores/{storeId}/menus/categories/{categoryId}",
                            store.getStoreId(), coffeeCategory.getCategoryId())
                            .with(customer(testCustomer.getCustomerId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data", hasSize(1)))
                    .andExpect(jsonPath("$.data[0].menuName").value("아메리카노"));
        }

        @Test
        @DisplayName("성공: 존재하지 않는 카테고리 조회시 빈 배열 반환")
        void success_categoryNotFound_emptyArray() throws Exception {
            // 서비스 코드에서 카테고리 존재 여부를 검증하지 않고 바로 쿼리 수행
            // 따라서 존재하지 않는 카테고리 ID로 조회해도 빈 배열만 반환

            mockMvc.perform(get("/stores/{storeId}/menus/categories/{categoryId}",
                            store.getStoreId(), 999999L)
                            .with(customer(testCustomer.getCustomerId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data", hasSize(0)));
        }
    }
}