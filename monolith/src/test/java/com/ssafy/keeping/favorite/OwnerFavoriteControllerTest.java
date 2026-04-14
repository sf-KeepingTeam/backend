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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
public class OwnerFavoriteControllerTest extends MySqlTestContainerConfig {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    OwnerRepository ownerRepository;

    @Autowired
    StoreRepository storeRepository;

    @Autowired
    CustomerRepository customerRepository;

    @Autowired
    StoreFavoriteRepository storeFavoriteRepository;

    // ============ Helper Methods ============

    private Authentication createOwnerAuth(Long ownerId) {
        UserPrincipal principal = new UserPrincipal(ownerId, UserRole.OWNER);
        return new UsernamePasswordAuthenticationToken(
                principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_OWNER"))
        );
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

    private StoreFavorite createTestFavorite(Customer customer, Store store) {
        return storeFavoriteRepository.save(StoreFavorite.builder()
                .customer(customer)
                .store(store)
                .active(true)
                .build());
    }

    // ============ GET /favorites/owner/stores/{storeId}/count ============

    @Nested
    @DisplayName("GET /favorites/owner/stores/{storeId}/count - 가게 찜 개수 조회")
    class GetStoreFavoriteCountTest {

        @Test
        @DisplayName("성공 - 찜 개수 조회")
        void getStoreFavoriteCount_success() throws Exception {
            // given
            Owner owner = createTestOwner();
            Store store = createTestStore(owner);
            Customer customer1 = createTestCustomer();
            Customer customer2 = createTestCustomer();
            createTestFavorite(customer1, store);
            createTestFavorite(customer2, store);
            Authentication auth = createOwnerAuth(owner.getOwnerId());

            // when & then
            mockMvc.perform(get("/favorites/owner/stores/{storeId}/count", store.getStoreId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("가게 찜 개수 조회에 성공했습니다."));
        }

        @Test
        @DisplayName("실패 - 인증 없음")
        void getStoreFavoriteCount_unauthorized() throws Exception {
            // when & then
            mockMvc.perform(get("/favorites/owner/stores/{storeId}/count", 1L))
                    .andExpect(status().isUnauthorized());
        }
    }
}
