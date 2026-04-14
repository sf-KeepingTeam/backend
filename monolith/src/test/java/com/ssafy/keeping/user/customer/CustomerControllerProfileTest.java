package com.ssafy.keeping.user.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.domain.auth.enums.AuthProvider;
import com.ssafy.keeping.domain.auth.enums.Gender;
import com.ssafy.keeping.domain.group.model.Group;
import com.ssafy.keeping.domain.group.model.GroupMember;
import com.ssafy.keeping.domain.group.repository.GroupMemberRepository;
import com.ssafy.keeping.domain.group.repository.GroupRepository;
import com.ssafy.keeping.domain.user.customer.dto.CustomerProfileUpdateRequest;
import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.user.customer.repository.CustomerRepository;
import com.ssafy.keeping.support.MySqlTestContainerConfig;
import org.junit.jupiter.api.DisplayName;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
public class CustomerControllerProfileTest extends MySqlTestContainerConfig {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired CustomerRepository customerRepository;
    @Autowired GroupRepository groupRepository;
    @Autowired GroupMemberRepository groupMemberRepository;

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
                .name("홍길동")
                .email("hong" + UUID.randomUUID() + "@test.com")
                .gender(Gender.MALE)
                .birth(LocalDate.of(1998, 1, 1))
                .imgUrl("https://test/img.png")
                .phoneNumber("010-1234-5678")
                .build());
    }

    private Group createTestGroup() {
        return groupRepository.save(Group.builder()
                .groupName("테스트그룹")
                .groupCode("TEST-" + UUID.randomUUID().toString().substring(0, 8))
                .groupDescription("테스트 그룹입니다")
                .build());
    }

    private GroupMember addMemberToGroup(Group group, Customer customer, boolean isLeader) {
        return groupMemberRepository.save(GroupMember.builder()
                .group(group)
                .user(customer)
                .leader(isLeader)
                .build());
    }

    // ============ Phase 1: GET /customers/me ============

    @Test
    @DisplayName("내 프로필 조회 성공 - /customers/me")
    void getMyProfile_success() throws Exception {
        // given
        Customer saved = createTestCustomer();
        Authentication auth = createAuth(saved.getCustomerId());

        // when & then
        mockMvc.perform(get("/customers/me")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("프로필 조회 성공"))
                .andExpect(jsonPath("$.data.name").value(saved.getName()))
                .andExpect(jsonPath("$.data.phoneNumber").value(saved.getPhoneNumber()))
                .andExpect(jsonPath("$.data.email").value(saved.getEmail()))
                .andExpect(jsonPath("$.data.imgUrl").value(saved.getImgUrl()));
    }

    @Test
    @DisplayName("내 프로필 조회 실패 - 존재하지 않는 고객")
    void getMyProfile_customerNotFound() throws Exception {
        // given
        Long nonExistentCustomerId = 99999L;
        Authentication auth = createAuth(nonExistentCustomerId);

        // when & then
        mockMvc.perform(get("/customers/me")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("내 프로필 조회 실패 - 인증 없음")
    void getMyProfile_unauthorized() throws Exception {
        // given - 인증 정보 없음

        // when & then
        mockMvc.perform(get("/customers/me"))
                .andExpect(status().isUnauthorized());
    }

    // ============ Phase 2: PUT /customers/me ============

    @Test
    @DisplayName("내 프로필 수정 성공")
    void updateMyProfile_success() throws Exception {
        // given
        Customer saved = createTestCustomer();
        Authentication auth = createAuth(saved.getCustomerId());
        CustomerProfileUpdateRequest request = new CustomerProfileUpdateRequest("김철수", "010-9999-8888");

        // when & then
        mockMvc.perform(put("/customers/me")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("프로필 수정 성공"))
                .andExpect(jsonPath("$.data.name").value("김철수"))
                .andExpect(jsonPath("$.data.phoneNumber").value("010-9999-8888"));
    }

    @Test
    @DisplayName("내 프로필 수정 실패 - 이름 빈값")
    void updateMyProfile_nameBlank() throws Exception {
        // given
        Customer saved = createTestCustomer();
        Authentication auth = createAuth(saved.getCustomerId());
        CustomerProfileUpdateRequest request = new CustomerProfileUpdateRequest("", "010-9999-8888");

        // when & then
        mockMvc.perform(put("/customers/me")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("내 프로필 수정 실패 - 전화번호 형식 오류")
    void updateMyProfile_phoneNumberInvalidFormat() throws Exception {
        // given
        Customer saved = createTestCustomer();
        Authentication auth = createAuth(saved.getCustomerId());
        CustomerProfileUpdateRequest request = new CustomerProfileUpdateRequest("김철수", "01012345678");

        // when & then
        mockMvc.perform(put("/customers/me")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("내 프로필 수정 실패 - 존재하지 않는 고객")
    void updateMyProfile_customerNotFound() throws Exception {
        // given
        Long nonExistentCustomerId = 99999L;
        Authentication auth = createAuth(nonExistentCustomerId);
        CustomerProfileUpdateRequest request = new CustomerProfileUpdateRequest("김철수", "010-9999-8888");

        // when & then
        mockMvc.perform(put("/customers/me")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("내 프로필 수정 실패 - 인증 없음")
    void updateMyProfile_unauthorized() throws Exception {
        // given
        CustomerProfileUpdateRequest request = new CustomerProfileUpdateRequest("김철수", "010-9999-8888");

        // when & then
        mockMvc.perform(put("/customers/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    // ============ Phase 3: GET /customers/me/groups ============

    @Test
    @DisplayName("내 그룹 조회 성공 - 그룹 있음")
    void myGroups_success_withGroups() throws Exception {
        // given
        Customer saved = createTestCustomer();
        Authentication auth = createAuth(saved.getCustomerId());

        Group group1 = createTestGroup();
        Group group2 = createTestGroup();
        addMemberToGroup(group1, saved, true);
        addMemberToGroup(group2, saved, false);

        // when & then
        mockMvc.perform(get("/customers/me/groups")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("내가 속한 그룹을 조회하였습니다."))
                .andExpect(jsonPath("$.data.groupIds").isArray())
                .andExpect(jsonPath("$.data.groupIds.length()").value(2));
    }

    @Test
    @DisplayName("내 그룹 조회 성공 - 그룹 없음")
    void myGroups_success_noGroups() throws Exception {
        // given
        Customer saved = createTestCustomer();
        Authentication auth = createAuth(saved.getCustomerId());

        // when & then
        mockMvc.perform(get("/customers/me/groups")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("내가 속한 그룹을 조회하였습니다."))
                .andExpect(jsonPath("$.data.groupIds").isArray())
                .andExpect(jsonPath("$.data.groupIds.length()").value(0));
    }

    @Test
    @DisplayName("내 그룹 조회 실패 - 인증 없음")
    void myGroups_unauthorized() throws Exception {
        // given - 인증 정보 없음

        // when & then
        mockMvc.perform(get("/customers/me/groups"))
                .andExpect(status().isUnauthorized());
    }
}