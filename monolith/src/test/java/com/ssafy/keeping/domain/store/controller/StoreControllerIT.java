package com.ssafy.keeping.domain.store.controller;

import com.ssafy.keeping.domain.menu.repository.MenuRepository;
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
import com.ssafy.keeping.testutil.fixture.OwnerFixtures;
import com.ssafy.keeping.testutil.fixture.StoreFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;
import static com.ssafy.keeping.testutil.security.TestAuth.customer;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("StoreController 통합 테스트")
class StoreControllerIT extends MySqlTestContainerConfig {

    @Autowired MockMvc mockMvc;
    @Autowired StoreRepository storeRepository;
    @Autowired OwnerRepository ownerRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired MenuRepository menuRepository;
    @Autowired MenuCategoryRepository menuCategoryRepository;

    Long activeStoreId;
    Long inactiveStoreId;

    Customer customer;
    Owner owner;

    @BeforeEach
    void setUp() {
        // FK 순서: menus -> categories -> stores -> owners
        menuRepository.deleteAllInBatch();
        menuCategoryRepository.deleteAllInBatch();
        storeRepository.deleteAllInBatch();
        ownerRepository.deleteAllInBatch();

        customer = customerRepository.save(CustomerFixtures.customer());
        owner = ownerRepository.save(OwnerFixtures.owner());


        Store active1 = storeRepository.save(StoreFixtures.store(owner, "활성매장1", StoreStatus.ACTIVE, "CAFE"));
        storeRepository.save(StoreFixtures.store(owner, "활성매장2", StoreStatus.ACTIVE, "FOOD"));
        storeRepository.save(StoreFixtures.store(owner, "활성매장3", StoreStatus.ACTIVE, "FOOD"));

        // ACTIVE만 조회되는지 확인용 (ACTIVE가 아닌 아무 상태면 됨)
        Store inactive = storeRepository.save(StoreFixtures.store(owner, "삭제매장", StoreStatus.DELETED, "FOOD"));

        activeStoreId = active1.getStoreId();
        inactiveStoreId = inactive.getStoreId();
    }

    @Test
    @DisplayName("전체 매장 조회(/stores): ACTIVE 상태 매장만 반환한다")
    void getAllStore_success_onlyActiveReturned() throws Exception {
        // @GetMapping(params={"!name","!category"}) → 쿼리파라미터 없이 /stores 로 호출해야 매핑됨
        mockMvc.perform(get("/stores")
                        .with(customer(customer.getCustomerId())))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("전체 매장이 조회되었습니다"))
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[*].storeName", hasItems("활성매장1", "활성매장2", "활성매장3")))
                .andExpect(jsonPath("$.data[*].storeStatus", everyItem(is("ACTIVE"))));
    }

    // storeId로 검색
    @Test
    @DisplayName("GET /stores/{storeId} - ACTIVE 매장은 조회 성공")
    void getStore_success_active() throws Exception {
        mockMvc.perform(get("/stores/{storeId}", activeStoreId)
                        .with(customer(customer.getCustomerId())))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("해당 store id로 매장이 조회되었습니다."))
                .andExpect(jsonPath("$.data.storeId").value(activeStoreId))
                .andExpect(jsonPath("$.data.storeName").value("활성매장1"))
                .andExpect(jsonPath("$.data.storeStatus").value("ACTIVE"));
    }

    @Test
    @DisplayName("GET /stores/{storeId} - 비활성(또는 DELETED) 매장은 STORE_NOT_FOUND")
    void getStore_fail_inactive() throws Exception {
        mockMvc.perform(get("/stores/{storeId}", inactiveStoreId)
                        .with(customer(customer.getCustomerId())))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.status").value(ErrorCode.STORE_NOT_FOUND.getHttpStatus().value()))
                .andExpect(jsonPath("$.message").value(ErrorCode.STORE_NOT_FOUND.getMessage()))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("GET /stores/{storeId} - 존재하지 않는 ID는 STORE_NOT_FOUND")
    void getStore_fail_notExists() throws Exception {
        mockMvc.perform(get("/stores/{storeId}", 999999L)
                        .with(customer(customer.getCustomerId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.status").value(ErrorCode.STORE_NOT_FOUND.getHttpStatus().value()))
                .andExpect(jsonPath("$.message").value(ErrorCode.STORE_NOT_FOUND.getMessage()))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    // ==== category로 Store 검색 ====
    @Test
    @DisplayName("GET /stores?category=FOOD - FOOD 카테고리의 ACTIVE 매장만 반환한다")
    void getAllStoreByCategory_success_food_onlyActiveFoodReturned() throws Exception {
        mockMvc.perform(get("/stores").param("category", "FOOD")
                        .with(customer(customer.getCustomerId())))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("해당 category로 매장이 조회되었습니다."))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].storeName", hasItems("활성매장2", "활성매장3")))
                .andExpect(jsonPath("$.data[*].category", everyItem(is("FOOD"))))
                .andExpect(jsonPath("$.data[*].storeStatus", everyItem(is("ACTIVE"))));
    }

    @Test
    @DisplayName("GET /stores?category=CAFE - CAFE 카테고리의 ACTIVE 매장만 반환한다")
    void getAllStoreByCategory_success_cafe_onlyActiveCafeReturned() throws Exception {
        mockMvc.perform(get("/stores").param("category", "CAFE")
                        .with(customer(customer.getCustomerId())))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("해당 category로 매장이 조회되었습니다."))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].storeName").value("활성매장1"))
                .andExpect(jsonPath("$.data[0].category").value("CAFE"))
                .andExpect(jsonPath("$.data[0].storeStatus").value("ACTIVE"));
    }

    @Test
    @DisplayName("GET /stores?category='   ' - 공백 category는 전체 ACTIVE 매장 조회로 처리한다")
    void getAllStoreByCategory_blankCategory_returnsAllActive() throws Exception {
        mockMvc.perform(get("/stores").param("category", "   ")
                        .with(customer(customer.getCustomerId())))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("해당 category로 매장이 조회되었습니다."))
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[*].storeName", hasItems("활성매장1", "활성매장2", "활성매장3")))
                .andExpect(jsonPath("$.data[*].storeStatus", everyItem(is("ACTIVE"))));
    }

    @Test
    @DisplayName("GET /stores?category=FOOD  - category 앞뒤 공백은 trim 처리된다")
    void getAllStoreByCategory_trimApplied() throws Exception {
        mockMvc.perform(get("/stores").param("category", "  FOOD  ")
                        .with(customer(customer.getCustomerId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[*].category", everyItem(is("FOOD"))));
    }

    // ==== name으로 Store 검색 ====
    @Test
    @DisplayName("GET /stores?name=활성매장 - 이름 부분일치로 ACTIVE 매장만 반환한다")
    void getStoreByName_success_similarity_onlyActiveReturned() throws Exception {
        mockMvc.perform(get("/stores").param("name", "활성매장")
                        .with(customer(customer.getCustomerId())))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/json"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.message").value("store name으로 매장이 조회되었습니다."))
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[*].storeName", hasItems("활성매장1", "활성매장2", "활성매장3")))
                .andExpect(jsonPath("$.data[*].storeStatus", everyItem(is("ACTIVE"))));
    }

    @Test
    @DisplayName("GET /stores?name=   - 공백 name은 전체 ACTIVE 매장 조회로 처리한다")
    void getStoreByName_blank_returnsAllActive() throws Exception {
        mockMvc.perform(get("/stores").param("name", "   ")
                        .with(customer(customer.getCustomerId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[*].storeName", hasItems("활성매장1", "활성매장2", "활성매장3")))
                .andExpect(jsonPath("$.data[*].storeStatus", everyItem(is("ACTIVE"))));
    }

    @Test
    @DisplayName("GET /stores?name=활성매장1 - 정확히 1개만 매칭되면 1개만 반환한다")
    void getStoreByName_success_exactOneMatched() throws Exception {
        mockMvc.perform(get("/stores").param("name", "활성매장1")
                        .with(customer(customer.getCustomerId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].storeName").value("활성매장1"))
                .andExpect(jsonPath("$.data[0].storeStatus").value("ACTIVE"));
    }

    @Test
    @DisplayName("GET /stores?name=삭제매장 - DELETED 매장은 검색 결과에서 제외된다")
    void getStoreByName_deletedExcluded() throws Exception {
        mockMvc.perform(get("/stores").param("name", "삭제매장")
                        .with(customer(customer.getCustomerId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    @DisplayName("GET /stores?name=HaLsEoNgMaEJang - 대소문자 무시하고 검색된다(lower like)")
    void getStoreByName_caseInsensitive() throws Exception {
        var owner = ownerRepository.findAll().get(0);
        storeRepository.save(StoreFixtures.store(owner, "CoffeeKing", StoreStatus.ACTIVE, "CAFE"));

        mockMvc.perform(get("/stores").param("name", "coffeek")
                        .with(customer(customer.getCustomerId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].storeName", hasItem("CoffeeKing")));
    }
}
