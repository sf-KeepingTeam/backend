package com.ssafy.keeping.charge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.domain.auth.enums.AuthProvider;
import com.ssafy.keeping.domain.auth.enums.Gender;
import com.ssafy.keeping.domain.charge.model.ChargeBonus;
import com.ssafy.keeping.domain.charge.repository.ChargeBonusRepository;
import com.ssafy.keeping.domain.store.constant.StoreStatus;
import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.store.repository.StoreRepository;
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
public class StoreChargeBonusControllerTest extends MySqlTestContainerConfig {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    OwnerRepository ownerRepository;

    @Autowired
    StoreRepository storeRepository;

    @Autowired
    ChargeBonusRepository chargeBonusRepository;

    // ============ Helper Methods ============

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

    private ChargeBonus createTestChargeBonus(Store store, Long chargeAmount, Integer bonusPercentage) {
        return chargeBonusRepository.save(ChargeBonus.builder()
                .store(store)
                .chargeAmount(chargeAmount)
                .bonusPercentage(bonusPercentage)
                .isActive(true)
                .build());
    }

    // ============ Helper Methods ============

    private Authentication createAuth(Long ownerId) {
        UserPrincipal principal = new UserPrincipal(ownerId, UserRole.OWNER);
        return new UsernamePasswordAuthenticationToken(
                principal, null,
                List.of(new SimpleGrantedAuthority("ROLE_OWNER"))
        );
    }

    // ============ GET /api/v1/stores/{storeId}/charge-bonus ============

    @Nested
    @DisplayName("GET /api/v1/stores/{storeId}/charge-bonus - 고객용 충전 보너스 목록 조회")
    class GetPublicChargeBonusListTest {

        @Test
        @DisplayName("성공 - 충전 보너스 목록 조회")
        void getPublicChargeBonusList_success() throws Exception {
            // given
            Owner owner = createTestOwner();
            Store store = createTestStore(owner);
            Authentication auth = createAuth(owner.getOwnerId());
            createTestChargeBonus(store, 10000L, 10);
            createTestChargeBonus(store, 20000L, 15);

            // when & then
            mockMvc.perform(get("/api/v1/stores/{storeId}/charge-bonus", store.getStoreId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("충전 보너스 목록이 조회되었습니다."))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(2));
        }

        @Test
        @DisplayName("성공 - 충전 보너스 없는 가게 조회")
        void getPublicChargeBonusList_emptyList() throws Exception {
            // given
            Owner owner = createTestOwner();
            Store store = createTestStore(owner);
            Authentication auth = createAuth(owner.getOwnerId());

            // when & then
            mockMvc.perform(get("/api/v1/stores/{storeId}/charge-bonus", store.getStoreId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("충전 보너스 목록이 조회되었습니다."))
                    .andExpect(jsonPath("$.data").isArray())
                    .andExpect(jsonPath("$.data.length()").value(0));
        }

        @Test
        @DisplayName("실패 - 존재하지 않는 가게")
        void getPublicChargeBonusList_storeNotFound() throws Exception {
            // given
            Owner owner = createTestOwner();
            Authentication auth = createAuth(owner.getOwnerId());

            // when & then
            mockMvc.perform(get("/api/v1/stores/{storeId}/charge-bonus", 99999L)
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                    .andExpect(status().isNotFound());
        }
    }
}
