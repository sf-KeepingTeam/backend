package com.ssafy.keeping.favorite;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.domain.auth.enums.AuthProvider;
import com.ssafy.keeping.domain.auth.enums.Gender;
import com.ssafy.keeping.domain.favorite.model.StoreFavorite;
import com.ssafy.keeping.domain.favorite.repository.StoreFavoriteRepository;
import com.ssafy.keeping.domain.store.constant.StoreStatus;
import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.store.repository.StoreRepository;
import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.user.customer.repository.CustomerRepository;
import com.ssafy.keeping.domain.user.owner.model.Owner;
import com.ssafy.keeping.domain.user.owner.repository.OwnerRepository;
import com.ssafy.keeping.support.MySqlTestContainerConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import com.ssafy.keeping.domain.auth.enums.UserRole;
import com.ssafy.keeping.domain.auth.security.principal.UserPrincipal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
public class StoreFavoriteControllerTest extends MySqlTestContainerConfig {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    CustomerRepository customerRepository;

    @Autowired
    OwnerRepository ownerRepository;

    @Autowired
    StoreRepository storeRepository;

    @Autowired
    StoreFavoriteRepository storeFavoriteRepository;

    // ============ Helper Methods ============

    private Authentication createAuth(Long customerId) {
        UserPrincipal principal = new UserPrincipal(customerId, UserRole.CUSTOMER);
        return new UsernamePasswordAuthenticationToken(
                principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
        );
    }

    private Customer createTestCustomer() {
        return customerRepository.save(Customer.builder()
                .providerType(AuthProvider.KAKAO)
                .providerId("kakao-" + UUID.randomUUID())
                .name("테스트고객")
                .email("test" + UUID.randomUUID() + "@test.com")
                .gender(Gender.MALE)
                .birth(LocalDate.of(1998, 1, 1))
                .imgUrl("https://test/img.png")
                .phoneNumber("010-1234-5678")
                .build());
    }

    private Owner createTestOwner() {
        return ownerRepository.save(Owner.builder()
                .providerType(AuthProvider.KAKAO)
                .providerId("kakao-owner-" + UUID.randomUUID())
                .name("테스트점주")
                .email("owner" + UUID.randomUUID() + "@test.com")
                .gender(Gender.MALE)
                .birth(LocalDate.of(1985, 5, 15))
                .imgUrl("https://test/owner.png")
                .phoneNumber("010-9999-8888")
                .build());
    }

    private Store createTestStore(Owner owner) {
        return storeRepository.save(Store.builder()
                .owner(owner)
                .taxIdNumber("123-45-" + UUID.randomUUID().toString().substring(0, 5))
                .storeName("테스트가게")
                .address("서울시 강남구 테스트동 123")
                .phoneNumber("02-1234-5678")
                .category("카페")
                .imgUrl("https://test/store.png")
                .description("테스트 가게입니다")
                .storeStatus(StoreStatus.ACTIVE)
                .build());
    }

    private StoreFavorite createTestFavorite(Customer customer, Store store) {
        return storeFavoriteRepository.save(StoreFavorite.builder()
                .customer(customer)
                .store(store)
                .active(true)
                .build());
    }

    // ============ POST /favorites/stores/{storeId} ============

    @Nested
    @DisplayName("POST /favorites/stores/{storeId} - 찜 토글")
    class ToggleFavoriteTest {

        @Test
        @DisplayName("성공 - 찜 추가")
        void toggleFavorite_add_success() throws Exception {
            // given
            Customer customer = createTestCustomer();
            Owner owner = createTestOwner();
            Store store = createTestStore(owner);
            Authentication auth = createAuth(customer.getCustomerId());

            // when & then
            mockMvc.perform(post("/favorites/stores/{storeId}", store.getStoreId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("성공 - 찜 취소")
        void toggleFavorite_cancel_success() throws Exception {
            // given
            Customer customer = createTestCustomer();
            Owner owner = createTestOwner();
            Store store = createTestStore(owner);
            createTestFavorite(customer, store);
            Authentication auth = createAuth(customer.getCustomerId());

            // when & then
            mockMvc.perform(post("/favorites/stores/{storeId}", store.getStoreId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("실패 - 인증 없음")
        void toggleFavorite_unauthorized() throws Exception {
            // when & then
            mockMvc.perform(post("/favorites/stores/{storeId}", 1L))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ============ GET /favorites ============

    @Nested
    @DisplayName("GET /favorites - 찜 목록 조회")
    class GetFavoriteStoresTest {

        @Test
        @DisplayName("성공 - 찜 목록 조회")
        void getFavoriteStores_success() throws Exception {
            // given
            Customer customer = createTestCustomer();
            Owner owner = createTestOwner();
            Store store1 = createTestStore(owner);
            Store store2 = createTestStore(owner);
            createTestFavorite(customer, store1);
            createTestFavorite(customer, store2);
            Authentication auth = createAuth(customer.getCustomerId());

            // when & then
            mockMvc.perform(get("/favorites")
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("찜 목록 조회에 성공했습니다."));
        }

        @Test
        @DisplayName("실패 - 인증 없음")
        void getFavoriteStores_unauthorized() throws Exception {
            // when & then
            mockMvc.perform(get("/favorites"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ============ GET /favorites/stores/{storeId}/check ============

    @Nested
    @DisplayName("GET /favorites/stores/{storeId}/check - 찜 상태 확인")
    class CheckFavoriteStatusTest {

        @Test
        @DisplayName("성공 - 찜 상태 확인")
        void checkFavoriteStatus_success() throws Exception {
            // given
            Customer customer = createTestCustomer();
            Owner owner = createTestOwner();
            Store store = createTestStore(owner);
            createTestFavorite(customer, store);
            Authentication auth = createAuth(customer.getCustomerId());

            // when & then
            mockMvc.perform(get("/favorites/stores/{storeId}/check", store.getStoreId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("찜 상태 확인에 성공했습니다."));
        }

        @Test
        @DisplayName("실패 - 인증 없음")
        void checkFavoriteStatus_unauthorized() throws Exception {
            // when & then
            mockMvc.perform(get("/favorites/stores/{storeId}/check", 1L))
                    .andExpect(status().isUnauthorized());
        }
    }
}
