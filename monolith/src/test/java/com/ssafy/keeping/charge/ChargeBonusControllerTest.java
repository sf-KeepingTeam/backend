package com.ssafy.keeping.charge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.domain.auth.enums.AuthProvider;
import com.ssafy.keeping.domain.auth.enums.Gender;
import com.ssafy.keeping.domain.charge.dto.request.ChargeBonusRequestDto;
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
import org.springframework.http.MediaType;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

//실패 모두 인증없음
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
public class ChargeBonusControllerTest extends MySqlTestContainerConfig {

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

    private ChargeBonus createTestChargeBonus(Store store, Long chargeAmount, Integer bonusPercentage) {
        return chargeBonusRepository.save(ChargeBonus.builder()
                .store(store)
                .chargeAmount(chargeAmount)
                .bonusPercentage(bonusPercentage)
                .isActive(true)
                .build());
    }

    // ============ POST /owners/stores/{storeId}/charge-bonus ============

    @Nested
    @DisplayName("POST /owners/stores/{storeId}/charge-bonus - 충전 보너스 생성")
    class CreateChargeBonusTest {

        @Test
        @DisplayName("성공 - 충전 보너스 생성")
        void createChargeBonus_success() throws Exception {
            // given
            Owner owner = createTestOwner();
            Store store = createTestStore(owner);
            Authentication auth = createOwnerAuth(owner.getOwnerId());
            ChargeBonusRequestDto request = new ChargeBonusRequestDto(10000L, 10);

            // when & then
            mockMvc.perform(post("/owners/stores/{storeId}/charge-bonus", store.getStoreId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.message").value("충전 보너스 설정이 생성되었습니다."));
        }

        @Test
        @DisplayName("실패 - 유효성 검증 실패 (충전 금액 부족)")
        void createChargeBonus_validationFail_amountTooLow() throws Exception {
            // given
            Owner owner = createTestOwner();
            Store store = createTestStore(owner);
            Authentication auth = createOwnerAuth(owner.getOwnerId());
            ChargeBonusRequestDto request = new ChargeBonusRequestDto(500L, 10);  // 최소 1000원

            // when & then
            mockMvc.perform(post("/owners/stores/{storeId}/charge-bonus", store.getStoreId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("실패 - 인증 없음")
        void createChargeBonus_unauthorized() throws Exception {
            // given
            ChargeBonusRequestDto request = new ChargeBonusRequestDto(10000L, 10);

            // when & then
            mockMvc.perform(post("/owners/stores/{storeId}/charge-bonus", 1L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ============ GET /owners/stores/{storeId}/charge-bonus ============

    @Nested
    @DisplayName("GET /owners/stores/{storeId}/charge-bonus - 충전 보너스 목록 조회")
    class GetChargeBonusListTest {

        @Test
        @DisplayName("성공 - 목록 조회")
        void getChargeBonusList_success() throws Exception {
            // given
            Owner owner = createTestOwner();
            Store store = createTestStore(owner);
            createTestChargeBonus(store, 10000L, 10);
            createTestChargeBonus(store, 20000L, 15);
            Authentication auth = createOwnerAuth(owner.getOwnerId());

            // when & then
            mockMvc.perform(get("/owners/stores/{storeId}/charge-bonus", store.getStoreId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("충전 보너스 설정 목록이 조회되었습니다."))
                    .andExpect(jsonPath("$.data").isArray());
        }

        @Test
        @DisplayName("실패 - 인증 없음")
        void getChargeBonusList_unauthorized() throws Exception {
            // when & then
            mockMvc.perform(get("/owners/stores/{storeId}/charge-bonus", 1L))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ============ GET /owners/stores/{storeId}/charge-bonus/{chargeBonusId} ============

    @Nested
    @DisplayName("GET /owners/stores/{storeId}/charge-bonus/{chargeBonusId} - 충전 보너스 상세 조회")
    class GetChargeBonusDetailTest {

        @Test
        @DisplayName("성공 - 상세 조회")
        void getChargeBonusDetail_success() throws Exception {
            // given
            Owner owner = createTestOwner();
            Store store = createTestStore(owner);
            ChargeBonus chargeBonus = createTestChargeBonus(store, 10000L, 10);
            Authentication auth = createOwnerAuth(owner.getOwnerId());

            // when & then
            mockMvc.perform(get("/owners/stores/{storeId}/charge-bonus/{chargeBonusId}",
                            store.getStoreId(), chargeBonus.getChargeBonusId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("충전 보너스 설정이 조회되었습니다."));
        }

        @Test
        @DisplayName("실패 - 존재하지 않는 충전 보너스")
        void getChargeBonusDetail_notFound() throws Exception {
            // given
            Owner owner = createTestOwner();
            Store store = createTestStore(owner);
            Authentication auth = createOwnerAuth(owner.getOwnerId());

            // when & then
            mockMvc.perform(get("/owners/stores/{storeId}/charge-bonus/{chargeBonusId}",
                            store.getStoreId(), 99999L)
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("실패 - 인증 없음")
        void getChargeBonusDetail_unauthorized() throws Exception {
            // when & then
            mockMvc.perform(get("/owners/stores/{storeId}/charge-bonus/{chargeBonusId}", 1L, 1L))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ============ PUT /owners/stores/{storeId}/charge-bonus/{chargeBonusId} ============
    @Nested
    @DisplayName("PUT /owners/stores/{storeId}/charge-bonus/{chargeBonusId} - 충전 보너스 수정")
    class UpdateChargeBonusTest {

        @Test
        @DisplayName("성공 - 수정")
        void updateChargeBonus_success() throws Exception {
            // given
            Owner owner = createTestOwner();
            Store store = createTestStore(owner);
            ChargeBonus chargeBonus = createTestChargeBonus(store, 10000L, 10);
            Authentication auth = createOwnerAuth(owner.getOwnerId());
            ChargeBonusRequestDto request = new ChargeBonusRequestDto(15000L, 20);

            // when & then
            mockMvc.perform(put("/owners/stores/{storeId}/charge-bonus/{chargeBonusId}",
                            store.getStoreId(), chargeBonus.getChargeBonusId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("충전 보너스 설정이 수정되었습니다."));
        }

        @Test
        @DisplayName("실패 - 존재하지 않는 충전 보너스")
        void updateChargeBonus_notFound() throws Exception {
            // given
            Owner owner = createTestOwner();
            Store store = createTestStore(owner);
            Authentication auth = createOwnerAuth(owner.getOwnerId());
            ChargeBonusRequestDto request = new ChargeBonusRequestDto(15000L, 20);

            // when & then
            mockMvc.perform(put("/owners/stores/{storeId}/charge-bonus/{chargeBonusId}",
                            store.getStoreId(), 99999L)
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("실패 - 인증 없음")
        void updateChargeBonus_unauthorized() throws Exception {
            // given
            ChargeBonusRequestDto request = new ChargeBonusRequestDto(15000L, 20);

            // when & then
            mockMvc.perform(put("/owners/stores/{storeId}/charge-bonus/{chargeBonusId}", 1L, 1L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ============ DELETE /owners/stores/{storeId}/charge-bonus/{chargeBonusId} ============

    @Nested
    @DisplayName("DELETE /owners/stores/{storeId}/charge-bonus/{chargeBonusId} - 충전 보너스 삭제")
    class DeleteChargeBonusTest {

        @Test
        @DisplayName("성공 - 삭제")
        void deleteChargeBonus_success() throws Exception {
            // given
            Owner owner = createTestOwner();
            Store store = createTestStore(owner);
            ChargeBonus chargeBonus = createTestChargeBonus(store, 10000L, 10);
            Authentication auth = createOwnerAuth(owner.getOwnerId());

            // when & then
            mockMvc.perform(delete("/owners/stores/{storeId}/charge-bonus/{chargeBonusId}",
                            store.getStoreId(), chargeBonus.getChargeBonusId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("충전 보너스 설정이 삭제되었습니다."));
        }

        @Test
        @DisplayName("실패 - 존재하지 않는 충전 보너스")
        void deleteChargeBonus_notFound() throws Exception {
            // given
            Owner owner = createTestOwner();
            Store store = createTestStore(owner);
            Authentication auth = createOwnerAuth(owner.getOwnerId());

            // when & then
            mockMvc.perform(delete("/owners/stores/{storeId}/charge-bonus/{chargeBonusId}",
                            store.getStoreId(), 99999L)
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                    .andExpect(status().isNotFound());
        }

        // 테스트 실패
        @Test
        @DisplayName("실패 - 인증 없음")
        void deleteChargeBonus_unauthorized() throws Exception {
            // when & then
            mockMvc.perform(delete("/owners/stores/{storeId}/charge-bonus/{chargeBonusId}", 1L, 1L))
                    .andExpect(status().isUnauthorized());
        }
    }
}
