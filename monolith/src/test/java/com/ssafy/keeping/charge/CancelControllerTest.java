package com.ssafy.keeping.charge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.domain.auth.enums.AuthProvider;
import com.ssafy.keeping.domain.auth.enums.Gender;
import com.ssafy.keeping.domain.charge.dto.request.CancelRequestDto;
import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.user.customer.repository.CustomerRepository;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
public class CancelControllerTest extends MySqlTestContainerConfig {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    CustomerRepository customerRepository;

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

    // ============ GET /api/v1/customers/cancel-list ============

    @Nested
    @DisplayName("GET /api/v1/customers/cancel-list - 취소 가능 거래 목록 조회")
    class GetCancelListTest {

        @Test
        @DisplayName("성공 - 취소 가능 거래 목록 조회")
        void getCancelList_success() throws Exception {
            // given
            Customer customer = createTestCustomer();
            Authentication auth = createAuth(customer.getCustomerId());

            // when & then
            mockMvc.perform(get("/api/v1/customers/cancel-list")
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("취소 가능한 거래 목록 조회가 완료되었습니다."));
        }

        @Test
        @DisplayName("실패 - 인증 없음")
        void getCancelList_unauthorized() throws Exception {
            // given - 인증 정보 없음

            // when & then
            mockMvc.perform(get("/api/v1/customers/cancel-list"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ============ POST /api/v1/customers/payments/cancel ============

    @Nested
    @DisplayName("POST /api/v1/customers/payments/cancel - 결제 취소")
    class CancelPaymentTest {

        @Test
        @DisplayName("실패 - 유효성 검증 실패 (취소 사유 누락)")
        void cancelPayment_validationFail_missingCancelReason() throws Exception {
            // given
            Customer customer = createTestCustomer();
            Authentication auth = createAuth(customer.getCustomerId());
            CancelRequestDto request = CancelRequestDto.builder()
                    .paymentKey("test-payment-key")
                    .cancelReason("")  // 빈 취소 사유
                    .build();

            // when & then
            mockMvc.perform(post("/api/v1/customers/payments/cancel")
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("실패 - 인증 없음")
        void cancelPayment_unauthorized() throws Exception {
            // given
            CancelRequestDto request = CancelRequestDto.builder()
                    .paymentKey("test-payment-key")
                    .cancelReason("테스트 취소")
                    .build();

            // when & then
            mockMvc.perform(post("/api/v1/customers/payments/cancel")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }
    }
}
