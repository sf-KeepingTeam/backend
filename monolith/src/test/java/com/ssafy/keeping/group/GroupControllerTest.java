package com.ssafy.keeping.group;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.domain.auth.enums.AuthProvider;
import com.ssafy.keeping.domain.auth.enums.Gender;
import com.ssafy.keeping.domain.group.constant.RequestStatus;
import com.ssafy.keeping.domain.group.dto.*;
import com.ssafy.keeping.domain.group.model.Group;
import com.ssafy.keeping.domain.group.model.GroupAddRequest;
import com.ssafy.keeping.domain.group.model.GroupMember;
import com.ssafy.keeping.domain.group.repository.GroupAddRequestRepository;
import com.ssafy.keeping.domain.group.repository.GroupMemberRepository;
import com.ssafy.keeping.domain.group.repository.GroupRepository;
import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.user.customer.repository.CustomerRepository;
import com.ssafy.keeping.domain.wallet.constant.WalletType;
import com.ssafy.keeping.domain.wallet.model.Wallet;
import com.ssafy.keeping.domain.wallet.repository.WalletRepository;
import com.ssafy.keeping.support.MySqlTestContainerConfig;
import jakarta.persistence.EntityManager;
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

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
public class GroupControllerTest extends MySqlTestContainerConfig {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    CustomerRepository customerRepository;

    @Autowired
    GroupRepository groupRepository;

    @Autowired
    GroupMemberRepository groupMemberRepository;

    @Autowired
    GroupAddRequestRepository groupAddRequestRepository;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    EntityManager entityManager;

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

    private GroupAddRequest createTestAddRequest(Group group, Customer customer) {
        return groupAddRequestRepository.save(GroupAddRequest.builder()
                .group(group)
                .user(customer)
                .requestStatus(RequestStatus.PENDING)
                .build());
    }

    private Wallet createGroupWallet(Group group) {
        return walletRepository.save(Wallet.builder()
                .group(group)
                .walletType(WalletType.GROUP)
                .build());
    }


    // ============ POST /groups ============

    @Nested
    @DisplayName("POST /groups - 그룹 생성")
    class CreateGroupTest {

        @Test
        @DisplayName("성공 - 그룹 생성")
        void createGroup_success() throws Exception {
            // given
            Customer customer = createTestCustomer();
            Authentication auth = createAuth(customer.getCustomerId());
            GroupRequestDto request = new GroupRequestDto("새로운그룹", "새로운 그룹입니다");

            // when & then
            mockMvc.perform(post("/groups")
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.message").value("모임이 생성되었습니다."));
        }

        @Test
        @DisplayName("실패 - 유효성 검증 실패 (그룹명 누락)")
        void createGroup_validationFail_missingName() throws Exception {
            // given
            Customer customer = createTestCustomer();
            Authentication auth = createAuth(customer.getCustomerId());
            GroupRequestDto request = new GroupRequestDto("", "설명입니다");

            // when & then
            mockMvc.perform(post("/groups")
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("실패 - 인증 없음")
        void createGroup_unauthorized() throws Exception {
            // given
            GroupRequestDto request = new GroupRequestDto("테스트그룹", "설명입니다");

            // when & then
            mockMvc.perform(post("/groups")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ============ GET /groups?name={name} ============

    @Nested
    @DisplayName("GET /groups?name={name} - 그룹 검색")
    class SearchGroupTest {

        @Test
        @DisplayName("성공 - 검색 결과 있음")
        void searchGroup_success_withResults() throws Exception {
            // given
            Customer customer = createTestCustomer();
            Group group = createTestGroup();

            addMemberToGroup(group, customer, true);

            entityManager.flush();

            Authentication auth = createAuth(customer.getCustomerId());

            // when & then
            mockMvc.perform(get("/groups")
                            .param("name", "테스트그룹")
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("해당 모임이 조회되었습니다."));
        }

        @Test
        @DisplayName("성공 - 검색 결과 없음")
        void searchGroup_success_noResults() throws Exception {
            // given
            Customer customer = createTestCustomer();
            Authentication auth = createAuth(customer.getCustomerId());

            // when & then
            mockMvc.perform(get("/groups")
                            .param("name", "존재하지않는그룹명")
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("해당 이름으로 조회되는 모임이 존재하지 않습니다."));
        }

        @Test
        @DisplayName("실패 - 인증 없음")
        void searchGroup_unauthorized() throws Exception {
            // when & then
            mockMvc.perform(get("/groups")
                            .param("name", "테스트"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ============ GET /groups/{groupId} ============

    @Nested
    @DisplayName("GET /groups/{groupId} - 그룹 상세 조회")
    class GetGroupTest {

        // 실패
        @Test
        @DisplayName("성공 - 그룹 상세 조회")
        void getGroup_success() throws Exception {
            // given
            Customer customer = createTestCustomer();
            Group group = createTestGroup();
            addMemberToGroup(group, customer, true);
            createGroupWallet(group);

            entityManager.flush();

            Authentication auth = createAuth(customer.getCustomerId());
            // when & then
            mockMvc.perform(get("/groups/{groupId}", group.getGroupId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("해당 모임이 조회되었습니다."));
        }

        @Test
        @DisplayName("실패 - 존재하지 않는 그룹")
        void getGroup_notFound() throws Exception {
            // given
            Customer customer = createTestCustomer();
            Authentication auth = createAuth(customer.getCustomerId());

            // when & then
            mockMvc.perform(get("/groups/{groupId}", 99999L)
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("실패 - 인증 없음")
        void getGroup_unauthorized() throws Exception {
            // when & then
            mockMvc.perform(get("/groups/{groupId}", 1L))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ============ PATCH /groups/{groupId} ============

    @Nested
    @DisplayName("PATCH /groups/{groupId} - 그룹 수정")
    class EditGroupTest {

        @Test
        @DisplayName("성공 - 그룹 수정")
        void editGroup_success() throws Exception {
            // given
            Customer customer = createTestCustomer();
            Group group = createTestGroup();
            addMemberToGroup(group, customer, true);  // 리더로 추가
            Authentication auth = createAuth(customer.getCustomerId());
            GroupEditRequestDto request = new GroupEditRequestDto("수정된그룹명", "수정된 설명입니다");

            // when & then
            mockMvc.perform(patch("/groups/{groupId}", group.getGroupId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("해당 모임이 수정되었습니다."));
        }

        @Test
        @DisplayName("실패 - 권한 없음 (리더가 아님)")
        void editGroup_notLeader() throws Exception {
            // given
            Customer leader = createTestCustomer();
            Customer member = createTestCustomer();
            Group group = createTestGroup();
            addMemberToGroup(group, leader, true);
            addMemberToGroup(group, member, false);
            Authentication auth = createAuth(member.getCustomerId());
            GroupEditRequestDto request = new GroupEditRequestDto("수정된그룹명", "수정된 설명입니다");

            // when & then
            mockMvc.perform(patch("/groups/{groupId}", group.getGroupId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("실패 - 인증 없음")
        void editGroup_unauthorized() throws Exception {
            // given
            GroupEditRequestDto request = new GroupEditRequestDto("수정된그룹명", "수정된 설명입니다");

            // when & then
            mockMvc.perform(patch("/groups/{groupId}", 1L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ============ GET /groups/{groupId}/group-members ============

    @Nested
    @DisplayName("GET /groups/{groupId}/group-members - 그룹 멤버 조회")
    class GetGroupMembersTest {

        @Test
        @DisplayName("성공 - 그룹 멤버 조회")
        void getGroupMembers_success() throws Exception {
            // given
            Customer customer = createTestCustomer();
            Group group = createTestGroup();
            addMemberToGroup(group, customer, true);
            Authentication auth = createAuth(customer.getCustomerId());

            // when & then
            mockMvc.perform(get("/groups/{groupId}/group-members", group.getGroupId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("해당 모임의 모임원들이 조회되었습니다."));
        }

        @Test
        @DisplayName("실패 - 인증 없음")
        void getGroupMembers_unauthorized() throws Exception {
            // when & then
            mockMvc.perform(get("/groups/{groupId}/group-members", 1L))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ============ POST /groups/{groupId}/add-requests ============

    @Nested
    @DisplayName("POST /groups/{groupId}/add-requests - 그룹 가입 신청")
    class CreateAddRequestTest {

        @Test
        @DisplayName("성공 - 가입 신청")
        void createAddRequest_success() throws Exception {
            // given
            Customer leader = createTestCustomer();
            Customer applicant = createTestCustomer();
            Group group = createTestGroup();
            addMemberToGroup(group, leader, true);
            Authentication auth = createAuth(applicant.getCustomerId());

            // when & then
            mockMvc.perform(post("/groups/{groupId}/add-requests", group.getGroupId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("해당 모임에 추가 신청을 완료했습니다."));
        }

        @Test
        @DisplayName("실패 - 인증 없음")
        void createAddRequest_unauthorized() throws Exception {
            // when & then
            mockMvc.perform(post("/groups/{groupId}/add-requests", 1L))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ============ GET /groups/{groupId}/add-requests ============

    @Nested
    @DisplayName("GET /groups/{groupId}/add-requests - 가입 신청 목록 조회")
    class GetAddRequestsTest {

        @Test
        @DisplayName("성공 - 가입 신청 목록 조회")
        void getAddRequests_success() throws Exception {
            // given
            Customer leader = createTestCustomer();
            Group group = createTestGroup();
            addMemberToGroup(group, leader, true);
            Authentication auth = createAuth(leader.getCustomerId());

            // when & then
            mockMvc.perform(get("/groups/{groupId}/add-requests", group.getGroupId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("모임 신청 내역을 조회했습니다."));
        }

        @Test
        @DisplayName("실패 - 인증 없음")
        void getAddRequests_unauthorized() throws Exception {
            // when & then
            mockMvc.perform(get("/groups/{groupId}/add-requests", 1L))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ============ PATCH /groups/{groupId}/add-requests ============

    @Nested
    @DisplayName("PATCH /groups/{groupId}/add-requests - 가입 신청 승인/거절")
    class UpdateAddRequestTest {

        @Test
        @DisplayName("성공 - 가입 신청 승인")
        void updateAddRequest_accept_success() throws Exception {
            // given
            Customer leader = createTestCustomer();
            Customer applicant = createTestCustomer();
            Group group = createTestGroup();
            addMemberToGroup(group, leader, true);
            GroupAddRequest addRequest = createTestAddRequest(group, applicant);
            Authentication auth = createAuth(leader.getCustomerId());
            AddRequestDecisionDto request = new AddRequestDecisionDto(addRequest.getGroupAddRequestId(), true);

            // when & then
            mockMvc.perform(patch("/groups/{groupId}/add-requests", group.getGroupId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("모임 추가 신청 승인 성공"));
        }

        @Test
        @DisplayName("실패 - 인증 없음")
        void updateAddRequest_unauthorized() throws Exception {
            // given
            AddRequestDecisionDto request = new AddRequestDecisionDto(1L, true);

            // when & then
            mockMvc.perform(patch("/groups/{groupId}/add-requests", 1L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ============ POST /groups/{groupId}/entrance ============

    @Nested
    @DisplayName("POST /groups/{groupId}/entrance - 그룹 입장")
    class EntranceGroupTest {

        @Test
        @DisplayName("성공 - 초대 코드로 입장")
        void entranceGroup_success() throws Exception {
            // given
            Customer leader = createTestCustomer();
            Customer newMember = createTestCustomer();
            Group group = createTestGroup();
            addMemberToGroup(group, leader, true);
            Authentication auth = createAuth(newMember.getCustomerId());
            createGroupWallet(group);

            entityManager.flush();

            GroupEntranceRequestDto request = new GroupEntranceRequestDto(group.getGroupCode());

            // when & then
            mockMvc.perform(post("/groups/{groupId}/entrance", group.getGroupId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("해당 모임에 입장을 완료했습니다."));
        }

        @Test
        @DisplayName("실패 - 초대 코드 불일치")
        void entranceGroup_wrongCode() throws Exception {
            // given
            Customer leader = createTestCustomer();
            Customer newMember = createTestCustomer();
            Group group = createTestGroup();
            createGroupWallet(group);

            addMemberToGroup(group, leader, true);

            Authentication auth = createAuth(newMember.getCustomerId());

            entityManager.flush();

            GroupEntranceRequestDto request = new GroupEntranceRequestDto("WRONG-CODE");

            // when & then
            mockMvc.perform(post("/groups/{groupId}/entrance", group.getGroupId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("실패 - 인증 없음")
        void entranceGroup_unauthorized() throws Exception {
            // given
            GroupEntranceRequestDto request = new GroupEntranceRequestDto("TEST-CODE");

            // when & then
            mockMvc.perform(post("/groups/{groupId}/entrance", 1L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ============ PATCH /groups/{groupId}/group-leader ============

    @Nested
    @DisplayName("PATCH /groups/{groupId}/group-leader - 그룹장 위임")
    class ChangeGroupLeaderTest {

        @Test
        @DisplayName("성공 - 그룹장 위임")
        void changeGroupLeader_success() throws Exception {
            // given
            Customer leader = createTestCustomer();
            Customer newLeader = createTestCustomer();
            Group group = createTestGroup();
            addMemberToGroup(group, leader, true);
            addMemberToGroup(group, newLeader, false);
            Authentication auth = createAuth(leader.getCustomerId());
            GroupLeaderChangeRequestDto request = new GroupLeaderChangeRequestDto(newLeader.getCustomerId());

            // when & then
            mockMvc.perform(patch("/groups/{groupId}/group-leader", group.getGroupId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("모임장 위임에 성공했습니다."));
        }

        @Test
        @DisplayName("실패 - 권한 없음 (리더가 아님)")
        void changeGroupLeader_notLeader() throws Exception {
            // given
            Customer leader = createTestCustomer();
            Customer member = createTestCustomer();
            Group group = createTestGroup();
            addMemberToGroup(group, leader, true);
            addMemberToGroup(group, member, false);
            Authentication auth = createAuth(member.getCustomerId());
            GroupLeaderChangeRequestDto request = new GroupLeaderChangeRequestDto(member.getCustomerId());

            // when & then
            mockMvc.perform(patch("/groups/{groupId}/group-leader", group.getGroupId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("실패 - 인증 없음")
        void changeGroupLeader_unauthorized() throws Exception {
            // given
            GroupLeaderChangeRequestDto request = new GroupLeaderChangeRequestDto(1L);

            // when & then
            mockMvc.perform(patch("/groups/{groupId}/group-leader", 1L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ============ POST /groups/{groupId}/group-member ============

    @Nested
    @DisplayName("POST /groups/{groupId}/group-member - 모임원 내보내기")
    class ExpelMemberTest {

        @Test
        @DisplayName("성공 - 모임원 내보내기")
        void expelMember_success() throws Exception {
            // given
            Customer leader = createTestCustomer();
            Customer target = createTestCustomer();
            Group group = createTestGroup();
            addMemberToGroup(group, leader, true);
            addMemberToGroup(group, target, false);
            createGroupWallet(group);

            entityManager.flush();

            Authentication auth = createAuth(leader.getCustomerId());
            GroupExpelRequestDto request = new GroupExpelRequestDto();
            request.setTargetCustomerId(target.getCustomerId());

            // when & then
            mockMvc.perform(post("/groups/{groupId}/group-member", group.getGroupId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("모임원을 내보냈습니다."));
        }

        @Test
        @DisplayName("실패 - 권한 없음 (리더가 아님)")
        void expelMember_notLeader() throws Exception {
            // given
            Customer leader = createTestCustomer();
            Customer member = createTestCustomer();
            Customer target = createTestCustomer();
            Group group = createTestGroup();
            addMemberToGroup(group, leader, true);
            addMemberToGroup(group, member, false);
            addMemberToGroup(group, target, false);
            Authentication auth = createAuth(member.getCustomerId());
            GroupExpelRequestDto request = new GroupExpelRequestDto();
            request.setTargetCustomerId(target.getCustomerId());

            // when & then
            mockMvc.perform(post("/groups/{groupId}/group-member", group.getGroupId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("실패 - 인증 없음")
        void expelMember_unauthorized() throws Exception {
            // given
            GroupExpelRequestDto request = new GroupExpelRequestDto();
            request.setTargetCustomerId(1L);

            // when & then
            mockMvc.perform(post("/groups/{groupId}/group-member", 1L)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ============ DELETE /groups/{groupId}/group-member ============

    @Nested
    @DisplayName("DELETE /groups/{groupId}/group-member - 모임 탈퇴")
    class LeaveGroupTest {

        // 실패
        @Test
        @DisplayName("성공 - 모임 탈퇴")
        void leaveGroup_success() throws Exception {
            // given
            Customer leader = createTestCustomer();
            Customer member = createTestCustomer();
            Group group = createTestGroup();
            addMemberToGroup(group, leader, true);
            addMemberToGroup(group, member, false);

            createGroupWallet(group);
            Authentication auth = createAuth(member.getCustomerId());

            // when & then
            mockMvc.perform(delete("/groups/{groupId}/group-member", group.getGroupId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("모임을 탈퇴했습니다."));
        }

        @Test
        @DisplayName("실패 - 인증 없음")
        void leaveGroup_unauthorized() throws Exception {
            // when & then
            mockMvc.perform(delete("/groups/{groupId}/group-member", 1L))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ============ DELETE /groups/{groupId} ============

    @Nested
    @DisplayName("DELETE /groups/{groupId} - 모임 해체")
    class DisbandGroupTest {

        // 실패
        @Test
        @DisplayName("성공 - 모임 해체")
        void disbandGroup_success() throws Exception {
            // given
            Customer leader = createTestCustomer();
            Group group = createTestGroup();
            addMemberToGroup(group, leader, true);
            createGroupWallet(group);

            Authentication auth = createAuth(leader.getCustomerId());

            // when & then
            mockMvc.perform(delete("/groups/{groupId}", group.getGroupId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("모임을 해체했습니다."));
        }

        @Test
        @DisplayName("실패 - 권한 없음 (리더가 아님)")
        void disbandGroup_notLeader() throws Exception {
            // given
            Customer leader = createTestCustomer();
            Customer member = createTestCustomer();
            Group group = createTestGroup();
            addMemberToGroup(group, leader, true);
            addMemberToGroup(group, member, false);
            Authentication auth = createAuth(member.getCustomerId());

            // when & then
            mockMvc.perform(delete("/groups/{groupId}", group.getGroupId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("실패 - 인증 없음")
        void disbandGroup_unauthorized() throws Exception {
            // when & then
            mockMvc.perform(delete("/groups/{groupId}", 1L))
                    .andExpect(status().isUnauthorized());
        }
    }
}
