package com.ssafy.keeping.domain.store.controller;

import com.ssafy.keeping.domain.auth.enums.AuthProvider;
import com.ssafy.keeping.domain.menu.repository.MenuRepository;
import com.ssafy.keeping.domain.menucategory.repository.MenuCategoryRepository;
import com.ssafy.keeping.domain.store.constant.StoreStatus;
import com.ssafy.keeping.domain.store.repository.StoreRepository;
import com.ssafy.keeping.domain.user.owner.repository.OwnerRepository;
import com.ssafy.keeping.domain.wallet.repository.WalletStoreBalanceRepository;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import com.ssafy.keeping.global.s3.service.ImageService;
import com.ssafy.keeping.support.MySqlTestContainerConfig;
import com.ssafy.keeping.testutil.fixture.OwnerFixtures;
import com.ssafy.keeping.testutil.fixture.StoreFixtures;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static com.ssafy.keeping.testutil.security.TestAuth.owner;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("OwnerStoreController 통합 테스트")
public class OwnerStoreControllerT extends MySqlTestContainerConfig {

    @Autowired MockMvc mockMvc;
    @Autowired StoreRepository storeRepository;
    @Autowired OwnerRepository ownerRepository;
    @Autowired MenuRepository menuRepository;
    @Autowired MenuCategoryRepository menuCategoryRepository;

    @MockBean ImageService imageService; // S3 대체
    @MockBean WalletStoreBalanceRepository balanceRepository; // 락/잔액 로직 대체

    Long ownerId;
    Long otherOwnerId;
    Long storeId;

    @BeforeEach
    void setUp() {
        menuRepository.deleteAllInBatch();
        menuCategoryRepository.deleteAllInBatch();
        storeRepository.deleteAllInBatch();
        ownerRepository.deleteAllInBatch();

        var owner = ownerRepository.save(OwnerFixtures.owner(AuthProvider.KAKAO, "kakao_" + UUID.randomUUID(), "점주1"));
        ownerId = owner.getOwnerId();

        var otherOwner = ownerRepository.save(OwnerFixtures.owner(AuthProvider.KAKAO, "kakao_" + UUID.randomUUID(), "점주2"));
        otherOwnerId = otherOwner.getOwnerId();

        var store = storeRepository.save(StoreFixtures.store(owner, "원래매장", StoreStatus.ACTIVE, "CAFE"));
        storeId = store.getStoreId();

        String dupTaxId = "123-45-67890";
        String dupAddress = "서울시 강남구 어딘가 1";
        storeRepository.save(StoreFixtures.storeWithTaxAndAddress(
                owner, "기준매장", StoreStatus.ACTIVE, "CAFE", dupTaxId, dupAddress
        ));

        // 이미지 서비스 기본 스텁
        when(imageService.uploadImage(any(MultipartFile.class), eq("store")))
                .thenReturn("https://example.com/uploaded-store.png");

        when(imageService.updateProfileImage(anyString(), any(MultipartFile.class)))
                .thenReturn("https://example.com/updated-store.png");
    }

    // ==== 매장 등록 ====
    @Test
    @DisplayName("POST /owners/stores - 매장 생성 성공(이미지는 Mock)")
    void createStore_success() throws Exception {
        MockMultipartFile imgFile = new MockMultipartFile(
                "imgFile", "store.png", "image/png", "dummy".getBytes()
        );

        mockMvc.perform(multipart("/owners/stores")
                        .file(imgFile)
                        .param("taxIdNumber", "111-22-33333")
                        .param("storeName", "새매장")
                        .param("address", "서울시 강남구 테스트 1")
                        .param("phoneNumber", "010-1111-2222")
                        .param("category", "CAFE")
                        .param("description", "설명")
                        .with(owner(ownerId))
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                )
                .andExpect(status().isCreated())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.message").value("매장이 등록되었습니다"))
                .andExpect(jsonPath("$.data.storeName").value("새매장"))
                .andExpect(jsonPath("$.data.storeStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.imgUrl").value("https://example.com/uploaded-store.png"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("POST /owners/stores - 같은 taxId+address면 STORE_ALREADY_EXISTS(409)")
    void createStore_fail_duplicate() throws Exception {
        // setUp에서 이미 store가 taxIdNumber=123-45-67890, address=서울시 강남구 어딘가 1 로 들어가 있음
        MockMultipartFile imgFile = new MockMultipartFile(
                "imgFile", "store.png", "image/png", "dummy".getBytes()
        );

        mockMvc.perform(multipart("/owners/stores")
                        .file(imgFile)
                        .param("taxIdNumber", "123-45-67890")
                        .param("storeName", "중복매장")
                        .param("address", "서울시 강남구 어딘가 1")
                        .param("category", "CAFE")
                        .with(owner(ownerId))
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.status").value(ErrorCode.STORE_ALREADY_EXISTS.getHttpStatus().value()))
                .andExpect(jsonPath("$.message").value(ErrorCode.STORE_ALREADY_EXISTS.getMessage()))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    // ==== 매장 수정 ====
    @Test
    @DisplayName("PATCH /owners/stores/{storeId} - 수정 성공(이미지 Mock)")
    void editStore_success() throws Exception {
        MockMultipartFile newImg = new MockMultipartFile(
                "imgFile", "new.png", "image/png", "dummy".getBytes()
        );

        mockMvc.perform(multipart("/owners/stores/{storeId}", storeId)
                        .file(newImg)
                        .param("storeName", "수정매장")
                        .param("address", "서울시 강남구 새주소 99") // 기존 주소랑 다르게(현재 로직상 같으면 중복으로 터질 수 있음)
                        .param("phoneNumber", "010-9999-8888")
                        .with(owner(ownerId))
                        .with(req -> { req.setMethod("PATCH"); return req; })
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("매장이 수정되었습니다"))
                .andExpect(jsonPath("$.data.storeId").value(storeId))
                .andExpect(jsonPath("$.data.storeName").value("수정매장"))
                .andExpect(jsonPath("$.data.imgUrl").value("https://example.com/updated-store.png"));
    }

    @Test
    @DisplayName("PATCH /owners/stores/{storeId} - 다른 점주가 수정하면 OWNER_NOT_MATCH(400)")
    void editStore_fail_ownerMismatch() throws Exception {
        MockMultipartFile newImg = new MockMultipartFile(
                "imgFile", "new.png", "image/png", "dummy".getBytes()
        );

        mockMvc.perform(multipart("/owners/stores/{storeId}", storeId)
                        .file(newImg)
                        .param("storeName", "수정시도")
                        .param("address", "서울시 강남구 새주소 100")
                        .with(owner(otherOwnerId))
                        .with(req -> { req.setMethod("PATCH"); return req; })
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.status").value(ErrorCode.OWNER_NOT_MATCH.getHttpStatus().value()))
                .andExpect(jsonPath("$.message").value(ErrorCode.OWNER_NOT_MATCH.getMessage()))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    // ==== 매장 삭제 ====
    @Test
    @DisplayName("DELETE /owners/stores/{storeId} - 잔액 없으면 DELETED")
    void deleteStore_success_deleted() throws Exception {
        when(balanceRepository.existsPositiveBalanceForStoreWithLock(storeId)).thenReturn(false);

        mockMvc.perform(delete("/owners/stores/{storeId}", storeId)
                        .with(owner(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("매장이 삭제되었습니다"))
                .andExpect(jsonPath("$.data.storeStatus").value("DELETED"));
    }

    @Test
    @DisplayName("DELETE /owners/stores/{storeId} - 잔액 있으면 SUSPENDED")
    void deleteStore_success_suspended() throws Exception {
        when(balanceRepository.existsPositiveBalanceForStoreWithLock(storeId)).thenReturn(true);

        mockMvc.perform(delete("/owners/stores/{storeId}", storeId)
                        .with(owner(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("매장이 삭제되었습니다"))
                .andExpect(jsonPath("$.data.storeStatus").value("SUSPENDED"));
    }

    // ==== 점주의 모든 매장 조회 ====
    @Test
    @DisplayName("GET /owners/stores - 내 매장 목록 조회 성공")
    void getMyStores_success() throws Exception {
        mockMvc.perform(get("/owners/stores")
                        .with(owner(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("내 매장 목록이 조회되었습니다."))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].storeName", hasItems("원래매장", "기준매장")));
    }

}


