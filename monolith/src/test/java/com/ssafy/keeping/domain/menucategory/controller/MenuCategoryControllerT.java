package com.ssafy.keeping.domain.menucategory.controller;

import com.ssafy.keeping.domain.auth.enums.AuthProvider;
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
import com.ssafy.keeping.testutil.fixture.OwnerFixtures;
import com.ssafy.keeping.testutil.fixture.StoreFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static com.ssafy.keeping.testutil.security.TestAuth.customer;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("MenuCategoryController 통합 테스트")
class MenuCategoryControllerT extends MySqlTestContainerConfig {

    @Autowired MockMvc mockMvc;
    @Autowired MenuRepository menuRepository;
    @Autowired MenuCategoryRepository menuCategoryRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired OwnerRepository ownerRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    Owner owner;
    Store store;
    Customer testCustomer;

    @BeforeEach
    void setUp() {
        // FK 체크 비활성화 후 삭제, 다시 활성화
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        jdbcTemplate.execute("DELETE FROM menus");
        jdbcTemplate.execute("DELETE FROM categories");
        jdbcTemplate.execute("DELETE FROM stores");
        jdbcTemplate.execute("DELETE FROM customers");
        jdbcTemplate.execute("DELETE FROM owners");
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");

        owner = ownerRepository.save(OwnerFixtures.owner(AuthProvider.KAKAO, "kakao_" + UUID.randomUUID(), "테스트점주"));
        store = storeRepository.save(StoreFixtures.store(owner, "테스트카페", StoreStatus.ACTIVE, "CAFE"));
        testCustomer = customerRepository.save(CustomerFixtures.customer());
    }

    @Nested
    @DisplayName("GET /stores/{storeId}/menus/categories - 대분류 카테고리 전체 조회")
    class GetAllMajorCategories {

        @Test
        @DisplayName("성공: 대분류 카테고리 전체 조회")
        void success() throws Exception {
            // 대분류 카테고리 생성
            MenuCategory coffee = menuCategoryRepository.save(MenuCategoryFixtures.category(store, "커피", 1));
            MenuCategory dessert = menuCategoryRepository.save(MenuCategoryFixtures.category(store, "디저트", 2));
            MenuCategory beverage = menuCategoryRepository.save(MenuCategoryFixtures.category(store, "음료", 3));

            mockMvc.perform(get("/stores/{storeId}/menus/categories", store.getStoreId())
                            .with(customer(testCustomer.getCustomerId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").value("해당 가게의 메뉴 카테고리(대분류)가 전체 조회되었습니다."))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data", hasSize(3)))
                    .andExpect(jsonPath("$.data[?(@.categoryName == '커피')].categoryId").value(org.hamcrest.Matchers.hasItem(coffee.getCategoryId().intValue())))
                    .andExpect(jsonPath("$.data[?(@.categoryName == '커피')].displayOrder").value(org.hamcrest.Matchers.hasItem(1)))
                    .andExpect(jsonPath("$.data[?(@.categoryName == '디저트')].categoryId").value(org.hamcrest.Matchers.hasItem(dessert.getCategoryId().intValue())))
                    .andExpect(jsonPath("$.data[?(@.categoryName == '음료')].categoryId").value(org.hamcrest.Matchers.hasItem(beverage.getCategoryId().intValue())));
        }

        @Test
        @DisplayName("성공: 대분류만 조회되고 소분류는 조회되지 않음")
        void success_onlyMajorCategories() throws Exception {
            // 대분류 카테고리
            MenuCategory coffee = menuCategoryRepository.save(MenuCategoryFixtures.category(store, "커피", 1));
            // 소분류 카테고리 (parent 있음)
            menuCategoryRepository.save(MenuCategoryFixtures.subCategory(store, coffee, "에스프레소", 1));
            menuCategoryRepository.save(MenuCategoryFixtures.subCategory(store, coffee, "라떼", 2));

            mockMvc.perform(get("/stores/{storeId}/menus/categories", store.getStoreId())
                            .with(customer(testCustomer.getCustomerId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data", hasSize(1)))
                    .andExpect(jsonPath("$.data[0].categoryName").value("커피"))
                    .andExpect(jsonPath("$.data[0].parentId").doesNotExist());
        }

        @Test
        @DisplayName("성공: 카테고리가 없는 경우 빈 배열 반환")
        void success_noCategories() throws Exception {
            mockMvc.perform(get("/stores/{storeId}/menus/categories", store.getStoreId())
                            .with(customer(testCustomer.getCustomerId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data", hasSize(0)));
        }

        @Test
        @DisplayName("실패: 존재하지 않는 가게 - STORE_NOT_FOUND")
        void fail_storeNotFound() throws Exception {
            mockMvc.perform(get("/stores/{storeId}/menus/categories", 999999L)
                            .with(customer(testCustomer.getCustomerId())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.STORE_NOT_FOUND.getHttpStatus().value()))
                    .andExpect(jsonPath("$.message").value(ErrorCode.STORE_NOT_FOUND.getMessage()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }
}