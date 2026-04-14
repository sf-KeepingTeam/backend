package com.ssafy.keeping.charge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.domain.auth.enums.AuthProvider;
import com.ssafy.keeping.domain.auth.enums.Gender;
import com.ssafy.keeping.domain.charge.dto.request.PrepaymentConfirmRequest;
import com.ssafy.keeping.domain.charge.dto.request.PrepaymentReserveRequest;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
public class PrepaymentControllerTest extends MySqlTestContainerConfig {

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

    // ============ POST /api/v1/stores/{storeId}/prepayment/reserve ============

    @Nested
    @DisplayName("POST /api/v1/stores/{storeId}/prepayment/reserve - 선결제 예약")
    class ReservePrepaymentTest {

        @Test
        @DisplayName("성공 - 선결제 예약")
        void reservePrepayment_success() throws Exception {
            // given
            Customer customer = createTestCustomer();
            Owner owner = createTestOwner();
            Store store = createTestStore(owner);
            Authentication auth = createAuth(customer.getCustomerId());
            PrepaymentReserveRequest request = PrepaymentReserveRequest.builder()
                    .amount(10000L)
                    .orderName("테스트 충전")
                    .build();

            // when & then
            mockMvc.perform(post("/api/v1/stores/{storeId}/prepayment/reserve", store.getStoreId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.message").value("결제 예약이 생성되었습니다."));
        }

        @Test
        @DisplayName("실패 - 유효성 검증 실패 (금액 누락)")
        void reservePrepayment_validationFail_nullAmount() throws Exception {
            // given
            Customer customer = createTestCustomer();
            Owner owner = createTestOwner();
            Store store = createTestStore(owner);
            Authentication auth = createAuth(customer.getCustomerId());
            PrepaymentReserveRequest request = PrepaymentReserveRequest.builder()
                    .amount(null)
                    .build();

            // when & then
            mockMvc.perform(post("/api/v1/stores/{storeId}/prepayment/reserve", store.getStoreId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("실패 - 유효성 검증 실패 (음수 금액)")
        void reservePrepayment_validationFail_negativeAmount() throws Exception {
            // given
            Customer customer = createTestCustomer();
            Owner owner = createTestOwner();
            Store store = createTestStore(owner);
            Authentication auth = createAuth(customer.getCustomerId());
            PrepaymentReserveRequest request = PrepaymentReserveRequest.builder()
                    .amount(-1000L)
                    .build();

            // when & then
            mockMvc.perform(post("/api/v1/stores/{storeId}/prepayment/reserve", store.getStoreId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        // 테스트 실패
        @Test
        @DisplayName("실패 - 인증 없음")
        void reservePrepayment_unauthorized() throws Exception {
            // given
            PrepaymentReserveRequest request = PrepaymentReserveRequest.builder()
                    .amount(10000L)
                    .build();

            // when & then
            mockMvc.perform(post("/api/v1/stores/{storeId}/prepayment/reserve", 1L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ============ POST /api/v1/stores/{storeId}/prepayment/confirm ============

    @Nested
    @DisplayName("POST /api/v1/stores/{storeId}/prepayment/confirm - 선결제 승인")
    class ConfirmPrepaymentTest {

        @Test
        @DisplayName("실패 - 유효성 검증 실패 (paymentKey 누락)")
        void confirmPrepayment_validationFail_missingPaymentKey() throws Exception {
            // given
            Customer customer = createTestCustomer();
            Owner owner = createTestOwner();
            Store store = createTestStore(owner);
            Authentication auth = createAuth(customer.getCustomerId());
            PrepaymentConfirmRequest request = PrepaymentConfirmRequest.builder()
                    .paymentKey("")  // 빈 값
                    .orderId("test-order-id")
                    .amount(10000L)
                    .build();

            // when & then
            mockMvc.perform(post("/api/v1/stores/{storeId}/prepayment/confirm", store.getStoreId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("실패 - 유효성 검증 실패 (orderId 누락)")
        void confirmPrepayment_validationFail_missingOrderId() throws Exception {
            // given
            Customer customer = createTestCustomer();
            Owner owner = createTestOwner();
            Store store = createTestStore(owner);
            Authentication auth = createAuth(customer.getCustomerId());
            PrepaymentConfirmRequest request = PrepaymentConfirmRequest.builder()
                    .paymentKey("test-payment-key")
                    .orderId("")  // 빈 값
                    .amount(10000L)
                    .build();

            // when & then
            mockMvc.perform(post("/api/v1/stores/{storeId}/prepayment/confirm", store.getStoreId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        // 테스트 실패
        @Test
        @DisplayName("실패 - 인증 없음")
        void confirmPrepayment_unauthorized() throws Exception {
            // given
            PrepaymentConfirmRequest request = PrepaymentConfirmRequest.builder()
                    .paymentKey("test-payment-key")
                    .orderId("test-order-id")
                    .amount(10000L)
                    .build();

            // when & then
            mockMvc.perform(post("/api/v1/stores/{storeId}/prepayment/confirm", 1L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }
    }
}
