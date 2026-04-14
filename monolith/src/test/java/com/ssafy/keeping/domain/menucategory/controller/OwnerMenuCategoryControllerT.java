package com.ssafy.keeping.domain.menucategory.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.domain.auth.enums.AuthProvider;
import com.ssafy.keeping.domain.menu.repository.MenuRepository;
import com.ssafy.keeping.domain.menucategory.dto.MenuCategoryEditRequestDto;
import com.ssafy.keeping.domain.menucategory.dto.MenuCategoryRequestDto;
import com.ssafy.keeping.domain.menucategory.model.MenuCategory;
import com.ssafy.keeping.domain.menucategory.repository.MenuCategoryRepository;
import com.ssafy.keeping.domain.store.constant.StoreStatus;
import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.store.repository.StoreRepository;
import com.ssafy.keeping.domain.user.owner.model.Owner;
import com.ssafy.keeping.domain.user.owner.repository.OwnerRepository;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import com.ssafy.keeping.support.MySqlTestContainerConfig;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static com.ssafy.keeping.testutil.security.TestAuth.owner;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("OwnerMenuCategoryController 통합 테스트")
class OwnerMenuCategoryControllerT extends MySqlTestContainerConfig {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired MenuRepository menuRepository;
    @Autowired MenuCategoryRepository menuCategoryRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired OwnerRepository ownerRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    Owner testOwner;
    Owner otherOwner;
    Store store;
    Store otherStore;

    @BeforeEach
    void setUp() {
        // FK 체크 비활성화 후 삭제, 다시 활성화
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 0");
        jdbcTemplate.execute("DELETE FROM menus");
        jdbcTemplate.execute("DELETE FROM categories");
        jdbcTemplate.execute("DELETE FROM stores");
        jdbcTemplate.execute("DELETE FROM owners");
        jdbcTemplate.execute("SET FOREIGN_KEY_CHECKS = 1");

        testOwner = ownerRepository.save(OwnerFixtures.owner(AuthProvider.KAKAO, "kakao_" + UUID.randomUUID(), "테스트점주"));
        otherOwner = ownerRepository.save(OwnerFixtures.owner(AuthProvider.KAKAO, "kakao_" + UUID.randomUUID(), "다른점주"));

        store = storeRepository.save(StoreFixtures.store(testOwner, "테스트카페", StoreStatus.ACTIVE, "CAFE"));
        otherStore = storeRepository.save(StoreFixtures.store(otherOwner, "다른카페", StoreStatus.ACTIVE, "CAFE"));
    }

    @Nested
    @DisplayName("POST /owners/stores/{storeId}/menus/categories - 카테고리 등록")
    class CreateMenuCategory {

        @Test
        @DisplayName("성공: 대분류 카테고리 등록")
        void success_majorCategory() throws Exception {
            MenuCategoryRequestDto request = new MenuCategoryRequestDto("커피", null);

            mockMvc.perform(post("/owners/stores/{storeId}/menus/categories", store.getStoreId())
                            .with(owner(testOwner.getOwnerId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.status").value(201))
                    .andExpect(jsonPath("$.message").value("메뉴 카테고리가 등록되었습니다"))
                    .andExpect(jsonPath("$.data.categoryName").value("커피"))
                    .andExpect(jsonPath("$.data.storeId").value(store.getStoreId()))
                    .andExpect(jsonPath("$.data.parentId").doesNotExist())
                    .andExpect(jsonPath("$.data.displayOrder").value(0));
        }

        @Test
        @DisplayName("성공: 소분류 카테고리 등록")
        void success_subCategory() throws Exception {
            // 대분류 먼저 생성
            MenuCategory parent = menuCategoryRepository.save(MenuCategoryFixtures.category(store, "커피", 1));

            MenuCategoryRequestDto request = new MenuCategoryRequestDto("에스프레소", parent.getCategoryId());

            mockMvc.perform(post("/owners/stores/{storeId}/menus/categories", store.getStoreId())
                            .with(owner(testOwner.getOwnerId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.categoryName").value("에스프레소"))
                    .andExpect(jsonPath("$.data.parentId").value(parent.getCategoryId()))
                    .andExpect(jsonPath("$.data.displayOrder").value(0));
        }

        @Test
        @DisplayName("성공: displayOrder 자동 증가")
        void success_displayOrderIncrement() throws Exception {
            // 기존 카테고리 2개 생성
            menuCategoryRepository.save(MenuCategoryFixtures.category(store, "커피", 1));
            menuCategoryRepository.save(MenuCategoryFixtures.category(store, "디저트", 2));

            MenuCategoryRequestDto request = new MenuCategoryRequestDto("음료", null);

            mockMvc.perform(post("/owners/stores/{storeId}/menus/categories", store.getStoreId())
                            .with(owner(testOwner.getOwnerId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.categoryName").value("음료"))
                    .andExpect(jsonPath("$.data.displayOrder").value(3));
        }

        @Test
        @DisplayName("실패: 다른 점주의 가게에 등록 시도 - OWNER_NOT_MATCH")
        void fail_ownerNotMatch() throws Exception {
            MenuCategoryRequestDto request = new MenuCategoryRequestDto("커피", null);

            mockMvc.perform(post("/owners/stores/{storeId}/menus/categories", store.getStoreId())
                            .with(owner(otherOwner.getOwnerId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.OWNER_NOT_MATCH.getHttpStatus().value()))
                    .andExpect(jsonPath("$.message").value(ErrorCode.OWNER_NOT_MATCH.getMessage()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("실패: 존재하지 않는 가게 - STORE_NOT_FOUND")
        void fail_storeNotFound() throws Exception {
            MenuCategoryRequestDto request = new MenuCategoryRequestDto("커피", null);

            mockMvc.perform(post("/owners/stores/{storeId}/menus/categories", 999999L)
                            .with(owner(testOwner.getOwnerId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.STORE_NOT_FOUND.getHttpStatus().value()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("실패: 존재하지 않는 부모 카테고리 - MENU_CATEGORY_NOT_FOUND")
        void fail_parentNotFound() throws Exception {
            MenuCategoryRequestDto request = new MenuCategoryRequestDto("에스프레소", 999999L);

            mockMvc.perform(post("/owners/stores/{storeId}/menus/categories", store.getStoreId())
                            .with(owner(testOwner.getOwnerId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.MENU_CATEGORY_NOT_FOUND.getHttpStatus().value()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("실패: 다른 가게의 카테고리를 부모로 지정 - STORE_NOT_MATCH")
        void fail_parentFromOtherStore() throws Exception {
            MenuCategory otherCategory = menuCategoryRepository.save(
                    MenuCategoryFixtures.category(otherStore, "다른카테고리", 1)
            );

            MenuCategoryRequestDto request = new MenuCategoryRequestDto("에스프레소", otherCategory.getCategoryId());

            mockMvc.perform(post("/owners/stores/{storeId}/menus/categories", store.getStoreId())
                            .with(owner(testOwner.getOwnerId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.STORE_NOT_MATCH.getHttpStatus().value()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("실패: 중복된 카테고리 이름 - DUPLICATE_RESOURCE")
        void fail_duplicateName() throws Exception {
            menuCategoryRepository.save(MenuCategoryFixtures.category(store, "커피", 1));

            MenuCategoryRequestDto request = new MenuCategoryRequestDto("커피", null);

            mockMvc.perform(post("/owners/stores/{storeId}/menus/categories", store.getStoreId())
                            .with(owner(testOwner.getOwnerId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.DUPLICATE_RESOURCE.getHttpStatus().value()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    @Nested
    @DisplayName("GET /owners/stores/{storeId}/menus/categories - 대분류 카테고리 전체 조회")
    class GetAllMajorCategories {

        @Test
        @DisplayName("성공: 대분류 카테고리 전체 조회")
        void success() throws Exception {
            menuCategoryRepository.save(MenuCategoryFixtures.category(store, "커피", 1));
            menuCategoryRepository.save(MenuCategoryFixtures.category(store, "디저트", 2));

            mockMvc.perform(get("/owners/stores/{storeId}/menus/categories", store.getStoreId())
                            .with(owner(testOwner.getOwnerId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").value("해당 가게의 메뉴 카테고리(대분류)가 전체 조회되었습니다."))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data", hasSize(2)));
        }

        @Test
        @DisplayName("성공: 카테고리가 없는 경우 빈 배열 반환")
        void success_noCategories() throws Exception {
            mockMvc.perform(get("/owners/stores/{storeId}/menus/categories", store.getStoreId())
                            .with(owner(testOwner.getOwnerId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data", hasSize(0)));
        }

        @Test
        @DisplayName("실패: 존재하지 않는 가게 - STORE_NOT_FOUND")
        void fail_storeNotFound() throws Exception {
            mockMvc.perform(get("/owners/stores/{storeId}/menus/categories", 999999L)
                            .with(owner(testOwner.getOwnerId())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.STORE_NOT_FOUND.getHttpStatus().value()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    @Nested
    @DisplayName("PATCH /owners/stores/{storeId}/menus/categories/{categoryId} - 카테고리 수정")
    class EditMenuCategory {

        MenuCategory existingCategory;

        @BeforeEach
        void setUpCategory() {
            existingCategory = menuCategoryRepository.save(MenuCategoryFixtures.category(store, "커피", 1));
        }

        @Test
        @DisplayName("성공: 카테고리 이름 수정")
        void success_editName() throws Exception {
            MenuCategoryEditRequestDto request = new MenuCategoryEditRequestDto("음료", null);

            mockMvc.perform(patch("/owners/stores/{storeId}/menus/categories/{categoryId}",
                            store.getStoreId(), existingCategory.getCategoryId())
                            .with(owner(testOwner.getOwnerId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").value("메뉴 카테고리가 수정되었습니다"))
                    .andExpect(jsonPath("$.data.categoryId").value(existingCategory.getCategoryId()))
                    .andExpect(jsonPath("$.data.categoryName").value("음료"));
        }

        @Test
        @DisplayName("성공: 대분류를 소분류로 변경")
        void success_changeToSubCategory() throws Exception {
            MenuCategory newParent = menuCategoryRepository.save(MenuCategoryFixtures.category(store, "음료", 2));

            MenuCategoryEditRequestDto request = new MenuCategoryEditRequestDto("커피", newParent.getCategoryId());

            mockMvc.perform(patch("/owners/stores/{storeId}/menus/categories/{categoryId}",
                            store.getStoreId(), existingCategory.getCategoryId())
                            .with(owner(testOwner.getOwnerId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.parentId").value(newParent.getCategoryId()))
                    .andExpect(jsonPath("$.data.displayOrder").value(0));
        }

        @Test
        @DisplayName("실패: 다른 점주가 수정 시도 - OWNER_NOT_MATCH")
        void fail_ownerNotMatch() throws Exception {
            MenuCategoryEditRequestDto request = new MenuCategoryEditRequestDto("음료", null);

            mockMvc.perform(patch("/owners/stores/{storeId}/menus/categories/{categoryId}",
                            store.getStoreId(), existingCategory.getCategoryId())
                            .with(owner(otherOwner.getOwnerId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.OWNER_NOT_MATCH.getHttpStatus().value()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("실패: 존재하지 않는 카테고리 - MENU_CATEGORY_NOT_FOUND")
        void fail_categoryNotFound() throws Exception {
            MenuCategoryEditRequestDto request = new MenuCategoryEditRequestDto("음료", null);

            mockMvc.perform(patch("/owners/stores/{storeId}/menus/categories/{categoryId}",
                            store.getStoreId(), 999999L)
                            .with(owner(testOwner.getOwnerId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.MENU_CATEGORY_NOT_FOUND.getHttpStatus().value()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("실패: 다른 가게의 카테고리를 부모로 지정 - STORE_NOT_MATCH")
        void fail_parentFromOtherStore() throws Exception {
            MenuCategory otherCategory = menuCategoryRepository.save(
                    MenuCategoryFixtures.category(otherStore, "다른카테고리", 1)
            );

            MenuCategoryEditRequestDto request = new MenuCategoryEditRequestDto("커피", otherCategory.getCategoryId());

            mockMvc.perform(patch("/owners/stores/{storeId}/menus/categories/{categoryId}",
                            store.getStoreId(), existingCategory.getCategoryId())
                            .with(owner(testOwner.getOwnerId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.STORE_NOT_MATCH.getHttpStatus().value()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("실패: 중복된 카테고리 이름으로 수정 - DUPLICATE_RESOURCE")
        void fail_duplicateName() throws Exception {
            menuCategoryRepository.save(MenuCategoryFixtures.category(store, "디저트", 2));

            MenuCategoryEditRequestDto request = new MenuCategoryEditRequestDto("디저트", null);

            mockMvc.perform(patch("/owners/stores/{storeId}/menus/categories/{categoryId}",
                            store.getStoreId(), existingCategory.getCategoryId())
                            .with(owner(testOwner.getOwnerId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.DUPLICATE_RESOURCE.getHttpStatus().value()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    @Nested
    @DisplayName("DELETE /owners/stores/{storeId}/menus/categories/{categoryId} - 카테고리 삭제")
    class DeleteMenuCategory {

        MenuCategory existingCategory;

        @BeforeEach
        void setUpCategory() {
            existingCategory = menuCategoryRepository.save(MenuCategoryFixtures.category(store, "커피", 1));
        }

        @Test
        @DisplayName("성공: 카테고리 삭제")
        void success() throws Exception {
            mockMvc.perform(delete("/owners/stores/{storeId}/menus/categories/{categoryId}",
                            store.getStoreId(), existingCategory.getCategoryId())
                            .with(owner(testOwner.getOwnerId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").value("메뉴 카테고리가 삭제되었습니다"))
                    .andExpect(jsonPath("$.data").doesNotExist());

            // 실제로 삭제되었는지 확인
            assert menuCategoryRepository.findById(existingCategory.getCategoryId()).isEmpty();
        }

        @Test
        @DisplayName("실패: 다른 점주가 삭제 시도 - OWNER_NOT_MATCH")
        void fail_ownerNotMatch() throws Exception {
            mockMvc.perform(delete("/owners/stores/{storeId}/menus/categories/{categoryId}",
                            store.getStoreId(), existingCategory.getCategoryId())
                            .with(owner(otherOwner.getOwnerId())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.OWNER_NOT_MATCH.getHttpStatus().value()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("실패: 존재하지 않는 카테고리 - MENU_CATEGORY_NOT_FOUND")
        void fail_categoryNotFound() throws Exception {
            mockMvc.perform(delete("/owners/stores/{storeId}/menus/categories/{categoryId}",
                            store.getStoreId(), 999999L)
                            .with(owner(testOwner.getOwnerId())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.MENU_CATEGORY_NOT_FOUND.getHttpStatus().value()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("실패: 다른 가게의 카테고리 삭제 시도 - STORE_NOT_MATCH")
        void fail_categoryFromOtherStore() throws Exception {
            MenuCategory otherCategory = menuCategoryRepository.save(
                    MenuCategoryFixtures.category(otherStore, "다른카테고리", 1)
            );

            mockMvc.perform(delete("/owners/stores/{storeId}/menus/categories/{categoryId}",
                            store.getStoreId(), otherCategory.getCategoryId())
                            .with(owner(testOwner.getOwnerId())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.STORE_NOT_MATCH.getHttpStatus().value()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("실패: 하위 카테고리가 있는 경우 - MENU_CATEGORY_HAS_CHILDREN")
        void fail_hasChildren() throws Exception {
            // 하위 카테고리 생성
            menuCategoryRepository.save(MenuCategoryFixtures.subCategory(store, existingCategory, "에스프레소", 1));

            mockMvc.perform(delete("/owners/stores/{storeId}/menus/categories/{categoryId}",
                            store.getStoreId(), existingCategory.getCategoryId())
                            .with(owner(testOwner.getOwnerId())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.MENU_CATEGORY_HAS_CHILDREN.getHttpStatus().value()))
                    .andExpect(jsonPath("$.message").value(ErrorCode.MENU_CATEGORY_HAS_CHILDREN.getMessage()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("실패: 존재하지 않는 가게 - STORE_NOT_FOUND")
        void fail_storeNotFound() throws Exception {
            mockMvc.perform(delete("/owners/stores/{storeId}/menus/categories/{categoryId}",
                            999999L, existingCategory.getCategoryId())
                            .with(owner(testOwner.getOwnerId())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.STORE_NOT_FOUND.getHttpStatus().value()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }
}