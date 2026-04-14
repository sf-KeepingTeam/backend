package com.ssafy.keeping.wallet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.domain.auth.enums.AuthProvider;
import com.ssafy.keeping.domain.auth.enums.Gender;
import com.ssafy.keeping.domain.group.model.Group;
import com.ssafy.keeping.domain.group.model.GroupMember;
import com.ssafy.keeping.domain.group.repository.GroupMemberRepository;
import com.ssafy.keeping.domain.group.repository.GroupRepository;
import com.ssafy.keeping.domain.store.constant.StoreStatus;
import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.store.repository.StoreRepository;
import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.user.customer.repository.CustomerRepository;
import com.ssafy.keeping.domain.user.owner.model.Owner;
import com.ssafy.keeping.domain.user.owner.repository.OwnerRepository;
import com.ssafy.keeping.domain.wallet.constant.LotSourceType;
import com.ssafy.keeping.domain.wallet.constant.LotStatus;
import com.ssafy.keeping.domain.wallet.constant.WalletType;
import com.ssafy.keeping.domain.wallet.dto.PointShareRequestDto;
import com.ssafy.keeping.domain.wallet.model.Wallet;
import com.ssafy.keeping.domain.wallet.model.WalletStoreBalance;
import com.ssafy.keeping.domain.wallet.model.WalletStoreLot;
import com.ssafy.keeping.domain.wallet.repository.WalletRepository;
import com.ssafy.keeping.domain.wallet.repository.WalletStoreBalanceRepository;
import com.ssafy.keeping.domain.wallet.repository.WalletStoreLotRepository;
import com.ssafy.keeping.domain.payment.transactions.constant.TransactionType;
import com.ssafy.keeping.domain.payment.transactions.model.Transaction;
import com.ssafy.keeping.domain.payment.transactions.repository.TransactionRepository;
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
public class WalletControllerTest extends MySqlTestContainerConfig {

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
    GroupRepository groupRepository;

    @Autowired
    GroupMemberRepository groupMemberRepository;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    WalletStoreBalanceRepository walletStoreBalanceRepository;

    @Autowired
    WalletStoreLotRepository walletStoreLotRepository;

    @Autowired
    TransactionRepository transactionRepository;

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

    private Wallet createIndividualWallet(Customer customer) {
        return walletRepository.save(Wallet.builder()
                .customer(customer)
                .walletType(WalletType.INDIVIDUAL)
                .build());
    }

    private Wallet createGroupWallet(Group group) {
        return walletRepository.save(Wallet.builder()
                .group(group)
                .walletType(WalletType.GROUP)
                .build());
    }

    // ============ GET /wallets/groups/{groupId} ============

    @Nested
    @DisplayName("GET /wallets/groups/{groupId} - 모임 지갑 조회")
    class GetGroupWalletsTest {

        @Test
        @DisplayName("성공 - 모임 지갑 조회")
        void getGroupWallets_success() throws Exception {
            // given
            Customer customer = createTestCustomer();
            Group group = createTestGroup();
            addMemberToGroup(group, customer, true);
            createGroupWallet(group);
            Authentication auth = createAuth(customer.getCustomerId());

            // when & then
            mockMvc.perform(get("/wallets/groups/{groupId}", group.getGroupId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("모임 지갑 조회에 성공했습니다."));
        }

        @Test
        @DisplayName("실패 - 인증 없음")
        void getGroupWallets_unauthorized() throws Exception {
            // when & then
            mockMvc.perform(get("/wallets/groups/{groupId}", 1L))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ============ POST /wallets/groups/{groupId}/stores/{storeId} ============

    @Nested
    @DisplayName("POST /wallets/groups/{groupId}/stores/{storeId} - 포인트 공유")
    class SharePointsTest {

        @Test
        @DisplayName("성공 - 포인트 공유")
        void sharePoints_success() throws Exception {
            // given
            Customer customer = createTestCustomer();
            Owner owner = createTestOwner();
            Store store = createTestStore(owner);
            Group group = createTestGroup();
            addMemberToGroup(group, customer, true);

            Wallet individualWallet = createIndividualWallet(customer);
            Wallet groupWallet = createGroupWallet(group);

            // 개인 지갑에 잔액 생성
            WalletStoreBalance individualBalance = walletStoreBalanceRepository.save(
                    WalletStoreBalance.builder()
                            .wallet(individualWallet)
                            .store(store)
                            .balance(5000L)
                            .build()
            );

            // 원본 충전 트랜잭션 생성 (LOT에 필요)
            Transaction chargeTransaction = transactionRepository.save(
                    Transaction.builder()
                            .wallet(individualWallet)
                            .customer(customer)
                            .store(store)
                            .transactionType(TransactionType.CHARGE)
                            .amount(5000L)
                            .build()
            );

            // 개인 지갑에 LOT 생성
            walletStoreLotRepository.save(
                    WalletStoreLot.builder()
                            .wallet(individualWallet)
                            .store(store)
                            .amountTotal(5000L)
                            .amountRemaining(5000L)
                            .acquiredAt(java.time.LocalDateTime.now())
                            .expiredAt(java.time.LocalDateTime.now().plusYears(1))
                            .sourceType(LotSourceType.CHARGE)
                            .lotStatus(LotStatus.ACTIVE)
                            .originChargeTransaction(chargeTransaction)
                            .build()
            );

            Authentication auth = createAuth(customer.getCustomerId());

            PointShareRequestDto request = new PointShareRequestDto();
            request.setIndividualWalletId(individualWallet.getWalletId());
            request.setGroupWalletId(groupWallet.getWalletId());
            request.setShareAmount(1000L);

            // when & then
            mockMvc.perform(post("/wallets/groups/{groupId}/stores/{storeId}",
                            group.getGroupId(), store.getStoreId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth))
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.message").value("모임 지갑으로 포인트 공유를 완료했습니다."));
        }

        @Test
        @DisplayName("실패 - 인증 없음")
        void sharePoints_unauthorized() throws Exception {
            // given
            PointShareRequestDto request = new PointShareRequestDto();
            request.setIndividualWalletId(1L);
            request.setGroupWalletId(2L);
            request.setShareAmount(1000L);

            // when & then
            mockMvc.perform(post("/wallets/groups/{groupId}/stores/{storeId}", 1L, 1L)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ============ GET /wallets/{walletId}/stores/{storeId}/points/available ============

    @Nested
    @DisplayName("GET /wallets/{walletId}/stores/{storeId}/points/available - 회수 가능 포인트 조회")
    class GetReclaimablePointsTest {

        @Test
        @DisplayName("성공 - 회수 가능 포인트 조회")
        void getReclaimablePoints_success() throws Exception {
            // given
            Customer customer = createTestCustomer();
            Owner owner = createTestOwner();
            Store store = createTestStore(owner);
            Group group = createTestGroup();
            addMemberToGroup(group, customer, true);
            Wallet groupWallet = createGroupWallet(group);
            Authentication auth = createAuth(customer.getCustomerId());

            // when & then
            mockMvc.perform(get("/wallets/{walletId}/stores/{storeId}/points/available",
                            groupWallet.getWalletId(), store.getStoreId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("회수 가능한 포인트를 조회했습니다."));
        }

        @Test
        @DisplayName("실패 - 인증 없음")
        void getReclaimablePoints_unauthorized() throws Exception {
            // when & then
            mockMvc.perform(get("/wallets/{walletId}/stores/{storeId}/points/available", 1L, 1L))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ============ POST /wallets/groups/{groupId}/stores/{storeId}/reclaim ============

    @Nested
    @DisplayName("POST /wallets/groups/{groupId}/stores/{storeId}/reclaim - 포인트 회수")
    class ReclaimPointsTest {

        @Test
        @DisplayName("성공 - 포인트 회수")
        void reclaimPoints_success() throws Exception {
            // given
            Customer customer = createTestCustomer();
            Owner owner = createTestOwner();
            Store store = createTestStore(owner);
            Group group = createTestGroup();
            addMemberToGroup(group, customer, true);

            Wallet individualWallet = createIndividualWallet(customer);
            Wallet groupWallet = createGroupWallet(group);

            // 원본 충전 트랜잭션 생성 (LOT에 필요)
            Transaction chargeTransaction = transactionRepository.save(
                    Transaction.builder()
                            .wallet(individualWallet)
                            .customer(customer)
                            .store(store)
                            .transactionType(TransactionType.CHARGE)
                            .amount(5000L)
                            .build()
            );

            // 모임 지갑에 잔액 생성 (회수할 포인트)
            walletStoreBalanceRepository.save(
                    WalletStoreBalance.builder()
                            .wallet(groupWallet)
                            .store(store)
                            .balance(3000L)
                            .build()
            );

            // 모임 지갑에 LOT 생성 (contributorWallet이 개인 지갑이어야 회수 가능)
            walletStoreLotRepository.save(
                    WalletStoreLot.builder()
                            .wallet(groupWallet)
                            .store(store)
                            .amountTotal(3000L)
                            .amountRemaining(3000L)
                            .acquiredAt(java.time.LocalDateTime.now())
                            .expiredAt(java.time.LocalDateTime.now().plusYears(1))
                            .sourceType(LotSourceType.TRANSFER_IN)
                            .contributorWallet(individualWallet)
                            .lotStatus(LotStatus.ACTIVE)
                            .originChargeTransaction(chargeTransaction)
                            .build()
            );

            Authentication auth = createAuth(customer.getCustomerId());

            PointShareRequestDto request = new PointShareRequestDto();
            request.setIndividualWalletId(individualWallet.getWalletId());
            request.setGroupWalletId(groupWallet.getWalletId());
            request.setShareAmount(1000L);

            // when & then
            mockMvc.perform(post("/wallets/groups/{groupId}/stores/{storeId}/reclaim",
                            group.getGroupId(), store.getStoreId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth))
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.message").value("모임 지갑에서 포인트를 회수했습니다."));
        }

        @Test
        @DisplayName("실패 - 인증 없음")
        void reclaimPoints_unauthorized() throws Exception {
            // given
            PointShareRequestDto request = new PointShareRequestDto();
            request.setIndividualWalletId(1L);
            request.setGroupWalletId(2L);
            request.setShareAmount(1000L);

            // when & then
            mockMvc.perform(post("/wallets/groups/{groupId}/stores/{storeId}/reclaim", 1L, 1L)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ============ GET /wallets/individual/balance ============

    @Nested
    @DisplayName("GET /wallets/individual/balance - 개인 지갑 잔액 조회")
    class GetPersonalWalletBalanceTest {

        @Test
        @DisplayName("성공 - 개인 지갑 잔액 조회")
        void getPersonalWalletBalance_success() throws Exception {
            // given
            Customer customer = createTestCustomer();
            createIndividualWallet(customer);
            Authentication auth = createAuth(customer.getCustomerId());

            // when & then
            mockMvc.perform(get("/wallets/individual/balance")
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("개인 지갑 잔액 조회에 성공했습니다."));
        }

        @Test
        @DisplayName("실패 - 인증 없음")
        void getPersonalWalletBalance_unauthorized() throws Exception {
            // when & then
            mockMvc.perform(get("/wallets/individual/balance"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ============ GET /wallets/groups/{groupId}/balance ============

    @Nested
    @DisplayName("GET /wallets/groups/{groupId}/balance - 모임 지갑 잔액 조회")
    class GetGroupWalletBalanceTest {

        @Test
        @DisplayName("성공 - 모임 지갑 잔액 조회")
        void getGroupWalletBalance_success() throws Exception {
            // given
            Customer customer = createTestCustomer();
            Group group = createTestGroup();
            addMemberToGroup(group, customer, true);
            createGroupWallet(group);
            Authentication auth = createAuth(customer.getCustomerId());

            // when & then
            mockMvc.perform(get("/wallets/groups/{groupId}/balance", group.getGroupId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("모임 지갑 잔액 조회에 성공했습니다."));
        }

        @Test
        @DisplayName("실패 - 인증 없음")
        void getGroupWalletBalance_unauthorized() throws Exception {
            // when & then
            mockMvc.perform(get("/wallets/groups/{groupId}/balance", 1L))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ============ GET /wallets/individual/stores/{storeId}/detail ============

    @Nested
    @DisplayName("GET /wallets/individual/stores/{storeId}/detail - 개인 지갑 가게별 상세 조회")
    class GetPersonalWalletStoreDetailTest {

        @Test
        @DisplayName("성공 - 개인 지갑 가게별 상세 조회")
        void getPersonalWalletStoreDetail_success() throws Exception {
            // given
            Customer customer = createTestCustomer();
            Owner owner = createTestOwner();
            Store store = createTestStore(owner);
            createIndividualWallet(customer);
            Authentication auth = createAuth(customer.getCustomerId());

            // when & then
            mockMvc.perform(get("/wallets/individual/stores/{storeId}/detail", store.getStoreId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("개인 지갑 가게별 상세 정보 조회에 성공했습니다."));
        }

        @Test
        @DisplayName("실패 - 인증 없음")
        void getPersonalWalletStoreDetail_unauthorized() throws Exception {
            // when & then
            mockMvc.perform(get("/wallets/individual/stores/{storeId}/detail", 1L))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ============ GET /wallets/groups/{groupId}/stores/{storeId}/detail ============

    @Nested
    @DisplayName("GET /wallets/groups/{groupId}/stores/{storeId}/detail - 모임 지갑 가게별 상세 조회")
    class GetGroupWalletStoreDetailTest {

        @Test
        @DisplayName("성공 - 모임 지갑 가게별 상세 조회")
        void getGroupWalletStoreDetail_success() throws Exception {
            // given
            Customer customer = createTestCustomer();
            Owner owner = createTestOwner();
            Store store = createTestStore(owner);
            Group group = createTestGroup();
            addMemberToGroup(group, customer, true);
            createGroupWallet(group);
            Authentication auth = createAuth(customer.getCustomerId());

            // when & then
            mockMvc.perform(get("/wallets/groups/{groupId}/stores/{storeId}/detail",
                            group.getGroupId(), store.getStoreId())
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("모임 지갑 가게별 상세 정보 조회에 성공했습니다."));
        }

        @Test
        @DisplayName("실패 - 인증 없음")
        void getGroupWalletStoreDetail_unauthorized() throws Exception {
            // when & then
            mockMvc.perform(get("/wallets/groups/{groupId}/stores/{storeId}/detail", 1L, 1L))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ============ GET /wallets/both/balance ============

    @Nested
    @DisplayName("GET /wallets/both/balance - 개인+모임 지갑 통합 조회")
    class GetBothWalletBalanceTest {

        @Test
        @DisplayName("성공 - 개인+모임 지갑 통합 조회")
        void getBothWalletBalance_success() throws Exception {
            // given
            Customer customer = createTestCustomer();
            createIndividualWallet(customer);
            Authentication auth = createAuth(customer.getCustomerId());

            // when & then
            mockMvc.perform(get("/wallets/both/balance")
                            .with(SecurityMockMvcRequestPostProcessors.authentication(auth)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value("개인 지갑 및 모임 지갑 조회에 성공했습니다."));
        }

        @Test
        @DisplayName("실패 - 인증 없음")
        void getBothWalletBalance_unauthorized() throws Exception {
            // when & then
            mockMvc.perform(get("/wallets/both/balance"))
                    .andExpect(status().isUnauthorized());
        }
    }
}
