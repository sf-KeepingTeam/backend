package com.ssafy.keeping.domain.menu.controller;

import com.ssafy.keeping.domain.auth.enums.AuthProvider;
import com.ssafy.keeping.domain.menu.model.Menu;
import com.ssafy.keeping.domain.menu.repository.MenuRepository;
import com.ssafy.keeping.domain.menucategory.model.MenuCategory;
import com.ssafy.keeping.domain.menucategory.repository.MenuCategoryRepository;
import com.ssafy.keeping.domain.store.constant.StoreStatus;
import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.store.repository.StoreRepository;
import com.ssafy.keeping.domain.user.owner.model.Owner;
import com.ssafy.keeping.domain.user.owner.repository.OwnerRepository;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import com.ssafy.keeping.global.s3.service.ImageService;
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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

import static com.ssafy.keeping.testutil.security.TestAuth.owner;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("OwnerMenuController 통합 테스트")
class OwnerMenuControllerT extends MySqlTestContainerConfig {

    @Autowired MockMvc mockMvc;
    @Autowired MenuRepository menuRepository;
    @Autowired MenuCategoryRepository menuCategoryRepository;
    @Autowired StoreRepository storeRepository;
    @Autowired OwnerRepository ownerRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockBean ImageService imageService;

    Owner testOwner;
    Owner otherOwner;
    Store store;
    Store otherStore;
    MenuCategory coffeeCategory;
    MenuCategory dessertCategory;

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

        coffeeCategory = menuCategoryRepository.save(MenuCategoryFixtures.category(store, "커피", 1));
        dessertCategory = menuCategoryRepository.save(MenuCategoryFixtures.category(store, "디저트", 2));

        // ImageService 기본 스텁
        when(imageService.uploadImage(any(MultipartFile.class), eq("menu")))
                .thenReturn("https://example.com/uploaded-menu.png");

        when(imageService.updateProfileImage(anyString(), any(MultipartFile.class)))
                .thenReturn("https://example.com/updated-menu.png");
    }

    @Nested
    @DisplayName("POST /owners/stores/{storeId}/menus - 메뉴 등록")
    class CreateMenu {

        @Test
        @DisplayName("성공: 메뉴 등록")
        void success() throws Exception {
            MockMultipartFile imgFile = new MockMultipartFile(
                    "imgFile", "menu.png", "image/png", "dummy".getBytes()
            );

            mockMvc.perform(multipart("/owners/stores/{storeId}/menus", store.getStoreId())
                            .file(imgFile)
                            .param("menuName", "아메리카노")
                            .param("categoryId", coffeeCategory.getCategoryId().toString())
                            .param("price", "4500")
                            .param("description", "시그니처 아메리카노")
                            .with(owner(testOwner.getOwnerId()))
                            .contentType(MediaType.MULTIPART_FORM_DATA))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.status").value(201))
                    .andExpect(jsonPath("$.message").value("메뉴가 등록되었습니다"))
                    .andExpect(jsonPath("$.data.menuName").value("아메리카노"))
                    .andExpect(jsonPath("$.data.storeId").value(store.getStoreId()))
                    .andExpect(jsonPath("$.data.categoryId").value(coffeeCategory.getCategoryId()))
                    .andExpect(jsonPath("$.data.categoryName").value("커피"))
                    .andExpect(jsonPath("$.data.price").value(4500))
                    .andExpect(jsonPath("$.data.description").value("시그니처 아메리카노"))
                    .andExpect(jsonPath("$.data.imgUrl").value("https://example.com/uploaded-menu.png"))
                    .andExpect(jsonPath("$.data.soldOut").value(false));
        }

        @Test
        @DisplayName("성공: description 없이 등록 시 빈 문자열로 저장")
        void success_withoutDescription() throws Exception {
            MockMultipartFile imgFile = new MockMultipartFile(
                    "imgFile", "menu.png", "image/png", "dummy".getBytes()
            );

            mockMvc.perform(multipart("/owners/stores/{storeId}/menus", store.getStoreId())
                            .file(imgFile)
                            .param("menuName", "카페라떼")
                            .param("categoryId", coffeeCategory.getCategoryId().toString())
                            .param("price", "5000")
                            .with(owner(testOwner.getOwnerId()))
                            .contentType(MediaType.MULTIPART_FORM_DATA))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.menuName").value("카페라떼"))
                    .andExpect(jsonPath("$.data.description").value(""));
        }

        @Test
        @DisplayName("실패: 다른 점주의 가게에 메뉴 등록 시도 - OWNER_NOT_MATCH")
        void fail_ownerNotMatch() throws Exception {
            MockMultipartFile imgFile = new MockMultipartFile(
                    "imgFile", "menu.png", "image/png", "dummy".getBytes()
            );

            mockMvc.perform(multipart("/owners/stores/{storeId}/menus", store.getStoreId())
                            .file(imgFile)
                            .param("menuName", "아메리카노")
                            .param("categoryId", coffeeCategory.getCategoryId().toString())
                            .param("price", "4500")
                            .with(owner(otherOwner.getOwnerId()))
                            .contentType(MediaType.MULTIPART_FORM_DATA))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.OWNER_NOT_MATCH.getHttpStatus().value()))
                    .andExpect(jsonPath("$.message").value(ErrorCode.OWNER_NOT_MATCH.getMessage()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("실패: 존재하지 않는 가게 - STORE_NOT_FOUND")
        void fail_storeNotFound() throws Exception {
            MockMultipartFile imgFile = new MockMultipartFile(
                    "imgFile", "menu.png", "image/png", "dummy".getBytes()
            );

            mockMvc.perform(multipart("/owners/stores/{storeId}/menus", 999999L)
                            .file(imgFile)
                            .param("menuName", "아메리카노")
                            .param("categoryId", coffeeCategory.getCategoryId().toString())
                            .param("price", "4500")
                            .with(owner(testOwner.getOwnerId()))
                            .contentType(MediaType.MULTIPART_FORM_DATA))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.STORE_NOT_FOUND.getHttpStatus().value()))
                    .andExpect(jsonPath("$.message").value(ErrorCode.STORE_NOT_FOUND.getMessage()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("실패: 존재하지 않는 카테고리 - MENU_CATEGORY_NOT_FOUND")
        void fail_categoryNotFound() throws Exception {
            MockMultipartFile imgFile = new MockMultipartFile(
                    "imgFile", "menu.png", "image/png", "dummy".getBytes()
            );

            mockMvc.perform(multipart("/owners/stores/{storeId}/menus", store.getStoreId())
                            .file(imgFile)
                            .param("menuName", "아메리카노")
                            .param("categoryId", "999999")
                            .param("price", "4500")
                            .with(owner(testOwner.getOwnerId()))
                            .contentType(MediaType.MULTIPART_FORM_DATA))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.MENU_CATEGORY_NOT_FOUND.getHttpStatus().value()))
                    .andExpect(jsonPath("$.message").value(ErrorCode.MENU_CATEGORY_NOT_FOUND.getMessage()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("실패: 다른 가게의 카테고리 사용 - STORE_NOT_MATCH")
        void fail_categoryFromOtherStore() throws Exception {
            MenuCategory otherCategory = menuCategoryRepository.save(
                    MenuCategoryFixtures.category(otherStore, "다른카테고리", 1)
            );

            MockMultipartFile imgFile = new MockMultipartFile(
                    "imgFile", "menu.png", "image/png", "dummy".getBytes()
            );

            mockMvc.perform(multipart("/owners/stores/{storeId}/menus", store.getStoreId())
                            .file(imgFile)
                            .param("menuName", "아메리카노")
                            .param("categoryId", otherCategory.getCategoryId().toString())
                            .param("price", "4500")
                            .with(owner(testOwner.getOwnerId()))
                            .contentType(MediaType.MULTIPART_FORM_DATA))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.STORE_NOT_MATCH.getHttpStatus().value()))
                    .andExpect(jsonPath("$.message").value(ErrorCode.STORE_NOT_MATCH.getMessage()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("실패: 중복된 메뉴 이름 - DUPLICATE_RESOURCE")
        void fail_duplicateMenuName() throws Exception {
            // 기존 메뉴 생성
            menuRepository.save(MenuFixtures.menu(store, coffeeCategory, "아메리카노", 4500));

            MockMultipartFile imgFile = new MockMultipartFile(
                    "imgFile", "menu.png", "image/png", "dummy".getBytes()
            );

            mockMvc.perform(multipart("/owners/stores/{storeId}/menus", store.getStoreId())
                            .file(imgFile)
                            .param("menuName", "아메리카노")
                            .param("categoryId", coffeeCategory.getCategoryId().toString())
                            .param("price", "5000")
                            .with(owner(testOwner.getOwnerId()))
                            .contentType(MediaType.MULTIPART_FORM_DATA))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.DUPLICATE_RESOURCE.getHttpStatus().value()))
                    .andExpect(jsonPath("$.message").value(ErrorCode.DUPLICATE_RESOURCE.getMessage()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("실패: 이미지 업로드 실패 - IMAGE_UPLOAD_ERROR")
        void fail_imageUploadError() throws Exception {
            // ImageService가 null 반환하도록 설정
            when(imageService.uploadImage(any(MultipartFile.class), eq("menu")))
                    .thenReturn(null);

            MockMultipartFile imgFile = new MockMultipartFile(
                    "imgFile", "menu.png", "image/png", "dummy".getBytes()
            );

            mockMvc.perform(multipart("/owners/stores/{storeId}/menus", store.getStoreId())
                            .file(imgFile)
                            .param("menuName", "아메리카노")
                            .param("categoryId", coffeeCategory.getCategoryId().toString())
                            .param("price", "4500")
                            .with(owner(testOwner.getOwnerId()))
                            .contentType(MediaType.MULTIPART_FORM_DATA))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.IMAGE_UPLOAD_ERROR.getHttpStatus().value()))
                    .andExpect(jsonPath("$.message").value(ErrorCode.IMAGE_UPLOAD_ERROR.getMessage()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    @Nested
    @DisplayName("PATCH /owners/stores/{storeId}/menus/{menusId} - 메뉴 수정")
    class EditMenu {

        Menu existingMenu;

        @BeforeEach
        void setUpMenu() {
            existingMenu = menuRepository.save(MenuFixtures.menu(store, coffeeCategory, "아메리카노", 4500, 1));
        }

        @Test
        @DisplayName("성공: 메뉴 수정")
        void success() throws Exception {
            MockMultipartFile imgFile = new MockMultipartFile(
                    "imgFile", "new-menu.png", "image/png", "dummy".getBytes()
            );

            mockMvc.perform(multipart("/owners/stores/{storeId}/menus/{menusId}",
                            store.getStoreId(), existingMenu.getMenuId())
                            .file(imgFile)
                            .param("menuName", "아이스 아메리카노")
                            .param("categoryId", coffeeCategory.getCategoryId().toString())
                            .param("price", "5000")
                            .param("description", "시원한 아메리카노")
                            .with(owner(testOwner.getOwnerId()))
                            .with(req -> { req.setMethod("PATCH"); return req; })
                            .contentType(MediaType.MULTIPART_FORM_DATA))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").value("메뉴가 수정되었습니다"))
                    .andExpect(jsonPath("$.data.menuId").value(existingMenu.getMenuId()))
                    .andExpect(jsonPath("$.data.menuName").value("아이스 아메리카노"))
                    .andExpect(jsonPath("$.data.price").value(5000))
                    .andExpect(jsonPath("$.data.description").value("시원한 아메리카노"))
                    .andExpect(jsonPath("$.data.imgUrl").value("https://example.com/updated-menu.png"));
        }

        @Test
        @DisplayName("성공: 카테고리 변경 시 displayOrder 재계산")
        void success_categoryChange() throws Exception {
            // 디저트 카테고리에 기존 메뉴 추가
            menuRepository.save(MenuFixtures.menu(store, dessertCategory, "치즈케이크", 6500, 1));

            MockMultipartFile imgFile = new MockMultipartFile(
                    "imgFile", "menu.png", "image/png", "dummy".getBytes()
            );

            // 아메리카노를 커피 → 디저트 카테고리로 변경
            mockMvc.perform(multipart("/owners/stores/{storeId}/menus/{menusId}",
                            store.getStoreId(), existingMenu.getMenuId())
                            .file(imgFile)
                            .param("menuName", "아메리카노")
                            .param("categoryId", dessertCategory.getCategoryId().toString())
                            .param("price", "4500")
                            .with(owner(testOwner.getOwnerId()))
                            .with(req -> { req.setMethod("PATCH"); return req; })
                            .contentType(MediaType.MULTIPART_FORM_DATA))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.categoryId").value(dessertCategory.getCategoryId()))
                    .andExpect(jsonPath("$.data.categoryName").value("디저트"))
                    .andExpect(jsonPath("$.data.displayOrder").value(2));
        }

        @Test
        @DisplayName("실패: 다른 점주가 수정 시도 - OWNER_NOT_MATCH")
        void fail_ownerNotMatch() throws Exception {
            MockMultipartFile imgFile = new MockMultipartFile(
                    "imgFile", "menu.png", "image/png", "dummy".getBytes()
            );

            mockMvc.perform(multipart("/owners/stores/{storeId}/menus/{menusId}",
                            store.getStoreId(), existingMenu.getMenuId())
                            .file(imgFile)
                            .param("menuName", "수정시도")
                            .param("categoryId", coffeeCategory.getCategoryId().toString())
                            .param("price", "5000")
                            .with(owner(otherOwner.getOwnerId()))
                            .with(req -> { req.setMethod("PATCH"); return req; })
                            .contentType(MediaType.MULTIPART_FORM_DATA))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.OWNER_NOT_MATCH.getHttpStatus().value()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("실패: 존재하지 않는 메뉴 - MENU_NOT_FOUND")
        void fail_menuNotFound() throws Exception {
            MockMultipartFile imgFile = new MockMultipartFile(
                    "imgFile", "menu.png", "image/png", "dummy".getBytes()
            );

            mockMvc.perform(multipart("/owners/stores/{storeId}/menus/{menusId}",
                            store.getStoreId(), 999999L)
                            .file(imgFile)
                            .param("menuName", "수정시도")
                            .param("categoryId", coffeeCategory.getCategoryId().toString())
                            .param("price", "5000")
                            .with(owner(testOwner.getOwnerId()))
                            .with(req -> { req.setMethod("PATCH"); return req; })
                            .contentType(MediaType.MULTIPART_FORM_DATA))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.MENU_NOT_FOUND.getHttpStatus().value()))
                    .andExpect(jsonPath("$.message").value(ErrorCode.MENU_NOT_FOUND.getMessage()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("실패: 다른 가게의 카테고리로 변경 시도 - STORE_NOT_MATCH")
        void fail_categoryFromOtherStore() throws Exception {
            MenuCategory otherCategory = menuCategoryRepository.save(
                    MenuCategoryFixtures.category(otherStore, "다른카테고리", 1)
            );

            MockMultipartFile imgFile = new MockMultipartFile(
                    "imgFile", "menu.png", "image/png", "dummy".getBytes()
            );

            mockMvc.perform(multipart("/owners/stores/{storeId}/menus/{menusId}",
                            store.getStoreId(), existingMenu.getMenuId())
                            .file(imgFile)
                            .param("menuName", "아메리카노")
                            .param("categoryId", otherCategory.getCategoryId().toString())
                            .param("price", "4500")
                            .with(owner(testOwner.getOwnerId()))
                            .with(req -> { req.setMethod("PATCH"); return req; })
                            .contentType(MediaType.MULTIPART_FORM_DATA))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.STORE_NOT_MATCH.getHttpStatus().value()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    @Nested
    @DisplayName("GET /owners/stores/{storeId}/menus - 점주용 메뉴 전체 조회")
    class GetAllMenusForOwner {

        @Test
        @DisplayName("성공: 점주가 자기 가게의 메뉴 조회 (inactive 포함)")
        void success() throws Exception {
            menuRepository.save(MenuFixtures.menu(store, coffeeCategory, "아메리카노", 4500, 1));
            menuRepository.save(MenuFixtures.inactiveMenu(store, coffeeCategory, "비공개메뉴", 3000, 2));
            menuRepository.save(MenuFixtures.menu(store, dessertCategory, "치즈케이크", 6500, 1));

            mockMvc.perform(get("/owners/stores/{storeId}/menus", store.getStoreId())
                            .with(owner(testOwner.getOwnerId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").value("메뉴가 전체 조회되었습니다"))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data", hasSize(3)))
                    .andExpect(jsonPath("$.data[?(@.menuName == '아메리카노')]").isNotEmpty())
                    .andExpect(jsonPath("$.data[?(@.menuName == '비공개메뉴')]").isNotEmpty())
                    .andExpect(jsonPath("$.data[?(@.menuName == '치즈케이크')]").isNotEmpty());
        }

        @Test
        @DisplayName("성공: 메뉴가 없는 경우 빈 배열 반환")
        void success_noMenus() throws Exception {
            mockMvc.perform(get("/owners/stores/{storeId}/menus", store.getStoreId())
                            .with(owner(testOwner.getOwnerId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data", hasSize(0)));
        }

        @Test
        @DisplayName("실패: 다른 점주가 조회 시도 - OWNER_NOT_MATCH")
        void fail_ownerNotMatch() throws Exception {
            mockMvc.perform(get("/owners/stores/{storeId}/menus", store.getStoreId())
                            .with(owner(otherOwner.getOwnerId())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.OWNER_NOT_MATCH.getHttpStatus().value()))
                    .andExpect(jsonPath("$.message").value(ErrorCode.OWNER_NOT_MATCH.getMessage()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("실패: 존재하지 않는 가게 - STORE_NOT_FOUND")
        void fail_storeNotFound() throws Exception {
            mockMvc.perform(get("/owners/stores/{storeId}/menus", 999999L)
                            .with(owner(testOwner.getOwnerId())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.STORE_NOT_FOUND.getHttpStatus().value()))
                    .andExpect(jsonPath("$.message").value(ErrorCode.STORE_NOT_FOUND.getMessage()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    @Nested
    @DisplayName("DELETE /owners/stores/{storeId}/menus/{menusId} - 단일 메뉴 삭제")
    class DeleteMenu {

        Menu existingMenu;

        @BeforeEach
        void setUpMenu() {
            existingMenu = menuRepository.save(MenuFixtures.menu(store, coffeeCategory, "아메리카노", 4500));
        }

        @Test
        @DisplayName("성공: 메뉴 삭제")
        void success() throws Exception {
            mockMvc.perform(delete("/owners/stores/{storeId}/menus/{menusId}",
                            store.getStoreId(), existingMenu.getMenuId())
                            .with(owner(testOwner.getOwnerId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").value("메뉴가 삭제 되었습니다"))
                    .andExpect(jsonPath("$.data").doesNotExist());

            // 실제로 삭제되었는지 확인
            assert menuRepository.findById(existingMenu.getMenuId()).isEmpty();
        }

        @Test
        @DisplayName("실패: 다른 점주가 삭제 시도 - OWNER_NOT_MATCH")
        void fail_ownerNotMatch() throws Exception {
            mockMvc.perform(delete("/owners/stores/{storeId}/menus/{menusId}",
                            store.getStoreId(), existingMenu.getMenuId())
                            .with(owner(otherOwner.getOwnerId())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.OWNER_NOT_MATCH.getHttpStatus().value()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("실패: 존재하지 않는 메뉴 - MENU_NOT_FOUND")
        void fail_menuNotFound() throws Exception {
            mockMvc.perform(delete("/owners/stores/{storeId}/menus/{menusId}",
                            store.getStoreId(), 999999L)
                            .with(owner(testOwner.getOwnerId())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.MENU_NOT_FOUND.getHttpStatus().value()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("실패: 존재하지 않는 가게 - STORE_NOT_FOUND")
        void fail_storeNotFound() throws Exception {
            mockMvc.perform(delete("/owners/stores/{storeId}/menus/{menusId}",
                            999999L, existingMenu.getMenuId())
                            .with(owner(testOwner.getOwnerId())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.STORE_NOT_FOUND.getHttpStatus().value()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    @Nested
    @DisplayName("DELETE /owners/stores/{storeId}/menus - 전체 메뉴 삭제")
    class DeleteAllMenus {

        @BeforeEach
        void setUpMenus() {
            menuRepository.save(MenuFixtures.menu(store, coffeeCategory, "아메리카노", 4500, 1));
            menuRepository.save(MenuFixtures.menu(store, coffeeCategory, "카페라떼", 5000, 2));
            menuRepository.save(MenuFixtures.menu(store, dessertCategory, "치즈케이크", 6500, 1));
        }

        @Test
        @DisplayName("성공: 가게의 전체 메뉴 삭제")
        void success() throws Exception {
            mockMvc.perform(delete("/owners/stores/{storeId}/menus", store.getStoreId())
                            .with(owner(testOwner.getOwnerId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").value("메뉴가 전체 삭제 되었습니다"))
                    .andExpect(jsonPath("$.data").doesNotExist());

            // 실제로 전체 삭제되었는지 확인
            assert menuRepository.findAll().stream()
                    .noneMatch(m -> m.getStore().getStoreId().equals(store.getStoreId()));
        }

        @Test
        @DisplayName("성공: 메뉴가 없는 가게에서 전체 삭제해도 성공")
        void success_noMenus() throws Exception {
            // setUp에서 생성된 메뉴 삭제
            menuRepository.deleteAllInBatch();

            mockMvc.perform(delete("/owners/stores/{storeId}/menus", store.getStoreId())
                            .with(owner(testOwner.getOwnerId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.message").value("메뉴가 전체 삭제 되었습니다"));
        }

        @Test
        @DisplayName("실패: 다른 점주가 전체 삭제 시도 - OWNER_NOT_MATCH")
        void fail_ownerNotMatch() throws Exception {
            mockMvc.perform(delete("/owners/stores/{storeId}/menus", store.getStoreId())
                            .with(owner(otherOwner.getOwnerId())))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.OWNER_NOT_MATCH.getHttpStatus().value()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("실패: 존재하지 않는 가게 - STORE_NOT_FOUND")
        void fail_storeNotFound() throws Exception {
            mockMvc.perform(delete("/owners/stores/{storeId}/menus", 999999L)
                            .with(owner(testOwner.getOwnerId())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.STORE_NOT_FOUND.getHttpStatus().value()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }
}