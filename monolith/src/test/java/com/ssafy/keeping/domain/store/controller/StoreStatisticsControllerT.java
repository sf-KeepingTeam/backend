package com.ssafy.keeping.domain.store.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.domain.auth.enums.AuthProvider;
import com.ssafy.keeping.domain.menu.repository.MenuRepository;
import com.ssafy.keeping.domain.menucategory.repository.MenuCategoryRepository;
import com.ssafy.keeping.domain.payment.transactions.model.Transaction;
import com.ssafy.keeping.domain.payment.transactions.repository.TransactionRepository;
import com.ssafy.keeping.domain.store.constant.StoreStatus;
import com.ssafy.keeping.domain.store.dto.StatisticsRequestDto;
import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.store.repository.StoreRepository;
import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.user.customer.repository.CustomerRepository;
import com.ssafy.keeping.domain.user.owner.model.Owner;
import com.ssafy.keeping.domain.user.owner.repository.OwnerRepository;
import com.ssafy.keeping.domain.wallet.model.Wallet;
import com.ssafy.keeping.domain.wallet.repository.WalletRepository;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import com.ssafy.keeping.support.MySqlTestContainerConfig;
import com.ssafy.keeping.testutil.fixture.CustomerFixtures;
import com.ssafy.keeping.testutil.fixture.OwnerFixtures;
import com.ssafy.keeping.testutil.fixture.StoreFixtures;
import com.ssafy.keeping.testutil.fixture.TransactionFixtures;
import com.ssafy.keeping.testutil.fixture.WalletFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static com.ssafy.keeping.testutil.security.TestAuth.owner;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@DisplayName("StoreStatisticsController 통합 테스트")
class StoreStatisticsControllerT extends MySqlTestContainerConfig {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired StoreRepository storeRepository;
    @Autowired OwnerRepository ownerRepository;
    @Autowired CustomerRepository customerRepository;
    @Autowired WalletRepository walletRepository;
    @Autowired TransactionRepository transactionRepository;
    @Autowired MenuRepository menuRepository;
    @Autowired MenuCategoryRepository menuCategoryRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    Owner testOwner;
    Owner otherOwner;
    Store testStore;
    Customer customer;
    Wallet wallet;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAllInBatch();
        walletRepository.deleteAllInBatch();
        menuRepository.deleteAllInBatch();
        menuCategoryRepository.deleteAllInBatch();
        storeRepository.deleteAllInBatch();
        customerRepository.deleteAllInBatch();
        ownerRepository.deleteAllInBatch();

        testOwner = ownerRepository.save(OwnerFixtures.owner(AuthProvider.KAKAO, "kakao_" + UUID.randomUUID(), "테스트점주"));
        otherOwner = ownerRepository.save(OwnerFixtures.owner(AuthProvider.KAKAO, "kakao_" + UUID.randomUUID(), "다른점주"));

        testStore = storeRepository.save(StoreFixtures.store(testOwner, "테스트매장", StoreStatus.ACTIVE, "CAFE"));

        customer = customerRepository.save(CustomerFixtures.customer());
        wallet = walletRepository.save(WalletFixtures.individualWallet(customer));
    }

    /**
     * Transaction 저장 후 createdAt을 원하는 시간으로 직접 UPDATE
     *
     * @CreationTimestamp가 persist 시점에 현재 시간으로 덮어쓰기 때문에,
     * JdbcTemplate으로 직접 UPDATE하여 테스트에서 원하는 날짜를 설정함
     */
    private Transaction saveTransactionWithCreatedAt(Transaction transaction, LocalDateTime createdAt) {
        Transaction saved = transactionRepository.save(transaction);
        jdbcTemplate.update(
                "UPDATE transactions SET created_at = ? WHERE transaction_id = ?",
                createdAt,
                saved.getTransactionId()
        );
        return saved;
    }

    /**
     * 테스트용 거래 데이터 생성
     *
     * 오늘:    CHARGE 10000, USE 5000
     * 어제:    CHARGE 20000, USE 8000
     * 지난주:  CHARGE 15000
     * 지난달:  CHARGE 30000, USE 12000
     *
     * 전체 통계 기준:
     * - 총 충전: 75000 (4건)
     * - 총 사용: 25000 (3건)
     * - 총 거래: 7건
     *
     * 지난주~오늘 (7일) 기준:
     * - 충전: 45000 (3건) - 오늘 10000 + 어제 20000 + 지난주 15000
     * - 사용: 13000 (2건) - 오늘 5000 + 어제 8000
     * - 총 거래: 5건
     */
    private void createTestTransactions() {
        LocalDateTime today = LocalDate.now().atStartOfDay().plusHours(10);
        LocalDateTime yesterday = today.minusDays(1);
        LocalDateTime lastWeek = today.minusWeeks(1);
        LocalDateTime lastMonth = today.minusMonths(1);

        // 오늘 거래: CHARGE 10000, USE 5000
        saveTransactionWithCreatedAt(
                TransactionFixtures.charge(wallet, customer, testStore, 10000L),
                today
        );
        saveTransactionWithCreatedAt(
                TransactionFixtures.use(wallet, customer, testStore, 5000L),
                today
        );

        // 어제 거래: CHARGE 20000, USE 8000
        saveTransactionWithCreatedAt(
                TransactionFixtures.charge(wallet, customer, testStore, 20000L),
                yesterday
        );
        saveTransactionWithCreatedAt(
                TransactionFixtures.use(wallet, customer, testStore, 8000L),
                yesterday
        );

        // 지난주 거래: CHARGE 15000
        saveTransactionWithCreatedAt(
                TransactionFixtures.charge(wallet, customer, testStore, 15000L),
                lastWeek
        );

        // 지난달 거래: CHARGE 30000, USE 12000
        saveTransactionWithCreatedAt(
                TransactionFixtures.charge(wallet, customer, testStore, 30000L),
                lastMonth
        );
        saveTransactionWithCreatedAt(
                TransactionFixtures.use(wallet, customer, testStore, 12000L),
                lastMonth
        );
    }

    @Nested
    @DisplayName("GET /stores/{storeId}/statistics/overall - 전체 통계 조회")
    class GetOverallStatistics {

        @Test
        @DisplayName("성공: 점주가 자기 가게의 전체 통계 조회")
        void success() throws Exception {
            createTestTransactions();
            // 전체 통계: 충전 75000(4건), 사용 25000(3건), 총 7건

            mockMvc.perform(get("/stores/{storeId}/statistics/overall", testStore.getStoreId())
                            .with(owner(testOwner.getOwnerId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").value("가게 전체 통계가 조회되었습니다."))
                    .andExpect(jsonPath("$.data.storeId").value(testStore.getStoreId()))
                    .andExpect(jsonPath("$.data.storeName").value("테스트매장"))
                    .andExpect(jsonPath("$.data.totalChargePoints").value(75000))
                    .andExpect(jsonPath("$.data.totalPointsUsed").value(25000))
                    .andExpect(jsonPath("$.data.totalTransactionCount").value(7))
                    .andExpect(jsonPath("$.data.totalChargeCount").value(4))
                    .andExpect(jsonPath("$.data.totalUseCount").value(3));
        }

        @Test
        @DisplayName("성공: 거래가 없는 경우 0으로 반환")
        void success_noTransactions() throws Exception {
            // 거래 데이터 생성하지 않음

            mockMvc.perform(get("/stores/{storeId}/statistics/overall", testStore.getStoreId())
                            .with(owner(testOwner.getOwnerId())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.totalChargePoints").value(0))
                    .andExpect(jsonPath("$.data.totalPointsUsed").value(0))
                    .andExpect(jsonPath("$.data.totalTransactionCount").value(0))
                    .andExpect(jsonPath("$.data.totalChargeCount").value(0))
                    .andExpect(jsonPath("$.data.totalUseCount").value(0));
        }

        @Test
        @DisplayName("실패: 다른 점주가 조회 시도 - UNAUTHORIZED_ACCESS")
        void fail_unauthorized() throws Exception {
            mockMvc.perform(get("/stores/{storeId}/statistics/overall", testStore.getStoreId())
                            .with(owner(otherOwner.getOwnerId())))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.UNAUTHORIZED_ACCESS.getHttpStatus().value()))
                    .andExpect(jsonPath("$.message").value(ErrorCode.UNAUTHORIZED_ACCESS.getMessage()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("실패: 존재하지 않는 가게 - STORE_NOT_FOUND")
        void fail_storeNotFound() throws Exception {
            mockMvc.perform(get("/stores/{storeId}/statistics/overall", 999999L)
                            .with(owner(testOwner.getOwnerId())))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.STORE_NOT_FOUND.getHttpStatus().value()))
                    .andExpect(jsonPath("$.message").value(ErrorCode.STORE_NOT_FOUND.getMessage()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    @Nested
    @DisplayName("POST /stores/{storeId}/statistics/daily - 일별 통계 조회")
    class GetDailyStatistics {

        @Test
        @DisplayName("성공: 오늘 날짜의 일별 통계 조회")
        void success_withDate() throws Exception {
            createTestTransactions();
            LocalDate today = LocalDate.now();
            // 오늘 거래: CHARGE 10000(1건), USE 5000(1건), 총 2건

            StatisticsRequestDto request = StatisticsRequestDto.builder()
                    .date(today)
                    .build();

            mockMvc.perform(post("/stores/{storeId}/statistics/daily", testStore.getStoreId())
                            .with(owner(testOwner.getOwnerId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").value("가게 일별 통계가 조회되었습니다."))
                    .andExpect(jsonPath("$.data.storeId").value(testStore.getStoreId()))
                    .andExpect(jsonPath("$.data.storeName").value("테스트매장"))
                    .andExpect(jsonPath("$.data.date").value(today.toString()))
                    .andExpect(jsonPath("$.data.dailyTotalChargePoints").value(10000))
                    .andExpect(jsonPath("$.data.dailyPointsUsed").value(5000))
                    .andExpect(jsonPath("$.data.dailyTransactionCount").value(2))
                    .andExpect(jsonPath("$.data.dailyChargeCount").value(1))
                    .andExpect(jsonPath("$.data.dailyUseCount").value(1));
        }

        @Test
        @DisplayName("성공: 날짜가 null이면 오늘 날짜로 조회")
        void success_nullDate_defaultsToToday() throws Exception {
            createTestTransactions();

            StatisticsRequestDto request = StatisticsRequestDto.builder().build();

            mockMvc.perform(post("/stores/{storeId}/statistics/daily", testStore.getStoreId())
                            .with(owner(testOwner.getOwnerId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.date").value(LocalDate.now().toString()))
                    .andExpect(jsonPath("$.data.dailyTotalChargePoints").value(10000))
                    .andExpect(jsonPath("$.data.dailyPointsUsed").value(5000));
        }

        @Test
        @DisplayName("성공: 거래 없는 날짜 조회시 0 반환")
        void success_noTransactionsOnDate() throws Exception {
            createTestTransactions();
            LocalDate noTransactionDate = LocalDate.now().minusYears(1);

            StatisticsRequestDto request = StatisticsRequestDto.builder()
                    .date(noTransactionDate)
                    .build();

            mockMvc.perform(post("/stores/{storeId}/statistics/daily", testStore.getStoreId())
                            .with(owner(testOwner.getOwnerId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.dailyTotalChargePoints").value(0))
                    .andExpect(jsonPath("$.data.dailyPointsUsed").value(0))
                    .andExpect(jsonPath("$.data.dailyTransactionCount").value(0));
        }

        @Test
        @DisplayName("실패: 다른 점주가 조회 시도 - UNAUTHORIZED_ACCESS")
        void fail_unauthorized() throws Exception {
            StatisticsRequestDto request = StatisticsRequestDto.builder()
                    .date(LocalDate.now())
                    .build();

            mockMvc.perform(post("/stores/{storeId}/statistics/daily", testStore.getStoreId())
                            .with(owner(otherOwner.getOwnerId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.UNAUTHORIZED_ACCESS.getHttpStatus().value()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("실패: 존재하지 않는 가게 - STORE_NOT_FOUND")
        void fail_storeNotFound() throws Exception {
            StatisticsRequestDto request = StatisticsRequestDto.builder()
                    .date(LocalDate.now())
                    .build();

            mockMvc.perform(post("/stores/{storeId}/statistics/daily", 999999L)
                            .with(owner(testOwner.getOwnerId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.STORE_NOT_FOUND.getHttpStatus().value()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    @Nested
    @DisplayName("POST /stores/{storeId}/statistics/period - 기간별 통계 조회")
    class GetPeriodStatistics {

        @Test
        @DisplayName("성공: 최근 일주일 기간별 통계 조회")
        void success() throws Exception {
            createTestTransactions();
            LocalDate startDate = LocalDate.now().minusWeeks(1);
            LocalDate endDate = LocalDate.now();
            // 지난주~오늘: CHARGE 10000+20000+15000=45000(3건), USE 5000+8000=13000(2건), 총 5건

            StatisticsRequestDto request = StatisticsRequestDto.builder()
                    .startDate(startDate)
                    .endDate(endDate)
                    .build();

            mockMvc.perform(post("/stores/{storeId}/statistics/period", testStore.getStoreId())
                            .with(owner(testOwner.getOwnerId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").value("가게 기간별 통계가 조회되었습니다."))
                    .andExpect(jsonPath("$.data.storeId").value(testStore.getStoreId()))
                    .andExpect(jsonPath("$.data.storeName").value("테스트매장"))
                    .andExpect(jsonPath("$.data.startDate").value(startDate.toString()))
                    .andExpect(jsonPath("$.data.endDate").value(endDate.toString()))
                    .andExpect(jsonPath("$.data.periodTotalChargePoints").value(45000))
                    .andExpect(jsonPath("$.data.periodPointsUsed").value(13000))
                    .andExpect(jsonPath("$.data.periodTransactionCount").value(5))
                    .andExpect(jsonPath("$.data.periodChargeCount").value(3))
                    .andExpect(jsonPath("$.data.periodUseCount").value(2))
                    .andExpect(jsonPath("$.data.averageDailyPayment").isNumber())
                    .andExpect(jsonPath("$.data.averageDailyPointsUsed").isNumber());
        }

        @Test
        @DisplayName("성공: 거래 없는 기간 조회시 0 반환")
        void success_noTransactionsInPeriod() throws Exception {
            createTestTransactions();
            LocalDate startDate = LocalDate.now().minusYears(2);
            LocalDate endDate = LocalDate.now().minusYears(1);

            StatisticsRequestDto request = StatisticsRequestDto.builder()
                    .startDate(startDate)
                    .endDate(endDate)
                    .build();

            mockMvc.perform(post("/stores/{storeId}/statistics/period", testStore.getStoreId())
                            .with(owner(testOwner.getOwnerId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.periodTotalChargePoints").value(0))
                    .andExpect(jsonPath("$.data.periodPointsUsed").value(0))
                    .andExpect(jsonPath("$.data.periodTransactionCount").value(0));
        }

        @Test
        @DisplayName("실패: startDate가 null - INVALID_DATE_RANGE")
        void fail_nullStartDate() throws Exception {
            StatisticsRequestDto request = StatisticsRequestDto.builder()
                    .endDate(LocalDate.now())
                    .build();

            mockMvc.perform(post("/stores/{storeId}/statistics/period", testStore.getStoreId())
                            .with(owner(testOwner.getOwnerId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.INVALID_DATE_RANGE.getHttpStatus().value()))
                    .andExpect(jsonPath("$.message").value(ErrorCode.INVALID_DATE_RANGE.getMessage()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("실패: endDate가 null - INVALID_DATE_RANGE")
        void fail_nullEndDate() throws Exception {
            StatisticsRequestDto request = StatisticsRequestDto.builder()
                    .startDate(LocalDate.now().minusWeeks(1))
                    .build();

            mockMvc.perform(post("/stores/{storeId}/statistics/period", testStore.getStoreId())
                            .with(owner(testOwner.getOwnerId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.INVALID_DATE_RANGE.getHttpStatus().value()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("실패: startDate > endDate - INVALID_DATE_RANGE")
        void fail_startDateAfterEndDate() throws Exception {
            StatisticsRequestDto request = StatisticsRequestDto.builder()
                    .startDate(LocalDate.now())
                    .endDate(LocalDate.now().minusWeeks(1))
                    .build();

            mockMvc.perform(post("/stores/{storeId}/statistics/period", testStore.getStoreId())
                            .with(owner(testOwner.getOwnerId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.INVALID_DATE_RANGE.getHttpStatus().value()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("실패: 다른 점주가 조회 시도 - UNAUTHORIZED_ACCESS")
        void fail_unauthorized() throws Exception {
            StatisticsRequestDto request = StatisticsRequestDto.builder()
                    .startDate(LocalDate.now().minusWeeks(1))
                    .endDate(LocalDate.now())
                    .build();

            mockMvc.perform(post("/stores/{storeId}/statistics/period", testStore.getStoreId())
                            .with(owner(otherOwner.getOwnerId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.UNAUTHORIZED_ACCESS.getHttpStatus().value()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("실패: 존재하지 않는 가게 - STORE_NOT_FOUND")
        void fail_storeNotFound() throws Exception {
            StatisticsRequestDto request = StatisticsRequestDto.builder()
                    .startDate(LocalDate.now().minusWeeks(1))
                    .endDate(LocalDate.now())
                    .build();

            mockMvc.perform(post("/stores/{storeId}/statistics/period", 999999L)
                            .with(owner(testOwner.getOwnerId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.STORE_NOT_FOUND.getHttpStatus().value()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }

    @Nested
    @DisplayName("POST /stores/{storeId}/statistics/monthly - 월별 통계 조회")
    class GetMonthlyStatistics {

        @Test
        @DisplayName("성공: 이번 달 월별 통계 조회")
        void success_withDate() throws Exception {
            createTestTransactions();
            LocalDate thisMonth = LocalDate.now().withDayOfMonth(1);
            // 이번 달 거래 (지난달 제외): 오늘, 어제, 지난주(같은 달이면)
            // 지난주가 같은 달에 있는지 여부에 따라 결과 달라짐

            StatisticsRequestDto request = StatisticsRequestDto.builder()
                    .date(thisMonth)
                    .build();

            mockMvc.perform(post("/stores/{storeId}/statistics/monthly", testStore.getStoreId())
                            .with(owner(testOwner.getOwnerId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.status").value(200))
                    .andExpect(jsonPath("$.message").value("가게 월별 통계가 조회되었습니다."))
                    .andExpect(jsonPath("$.data.storeId").value(testStore.getStoreId()))
                    .andExpect(jsonPath("$.data.storeName").value("테스트매장"))
                    .andExpect(jsonPath("$.data.year").value(thisMonth.getYear()))
                    .andExpect(jsonPath("$.data.month").value(thisMonth.getMonthValue()))
                    .andExpect(jsonPath("$.data.monthlyTotalChargePoints").isNumber())
                    .andExpect(jsonPath("$.data.monthlyPointsUsed").isNumber())
                    .andExpect(jsonPath("$.data.monthlyTransactionCount").isNumber())
                    .andExpect(jsonPath("$.data.monthlyChargeCount").isNumber())
                    .andExpect(jsonPath("$.data.monthlyUseCount").isNumber())
                    .andExpect(jsonPath("$.data.averageDailyPayment").isNumber())
                    .andExpect(jsonPath("$.data.averageDailyPointsUsed").isNumber());
        }

        @Test
        @DisplayName("성공: 날짜가 null이면 이번 달로 조회")
        void success_nullDate_defaultsToThisMonth() throws Exception {
            createTestTransactions();
            LocalDate now = LocalDate.now();

            StatisticsRequestDto request = StatisticsRequestDto.builder().build();

            mockMvc.perform(post("/stores/{storeId}/statistics/monthly", testStore.getStoreId())
                            .with(owner(testOwner.getOwnerId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.year").value(now.getYear()))
                    .andExpect(jsonPath("$.data.month").value(now.getMonthValue()));
        }

        @Test
        @DisplayName("성공: 거래 없는 월 조회시 0 반환")
        void success_noTransactionsInMonth() throws Exception {
            createTestTransactions();
            LocalDate noTransactionMonth = LocalDate.now().minusYears(1);

            StatisticsRequestDto request = StatisticsRequestDto.builder()
                    .date(noTransactionMonth)
                    .build();

            mockMvc.perform(post("/stores/{storeId}/statistics/monthly", testStore.getStoreId())
                            .with(owner(testOwner.getOwnerId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data.monthlyTotalChargePoints").value(0))
                    .andExpect(jsonPath("$.data.monthlyPointsUsed").value(0))
                    .andExpect(jsonPath("$.data.monthlyTransactionCount").value(0));
        }

        @Test
        @DisplayName("실패: 다른 점주가 조회 시도 - UNAUTHORIZED_ACCESS")
        void fail_unauthorized() throws Exception {
            StatisticsRequestDto request = StatisticsRequestDto.builder()
                    .date(LocalDate.now())
                    .build();

            mockMvc.perform(post("/stores/{storeId}/statistics/monthly", testStore.getStoreId())
                            .with(owner(otherOwner.getOwnerId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.UNAUTHORIZED_ACCESS.getHttpStatus().value()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }

        @Test
        @DisplayName("실패: 존재하지 않는 가게 - STORE_NOT_FOUND")
        void fail_storeNotFound() throws Exception {
            StatisticsRequestDto request = StatisticsRequestDto.builder()
                    .date(LocalDate.now())
                    .build();

            mockMvc.perform(post("/stores/{storeId}/statistics/monthly", 999999L)
                            .with(owner(testOwner.getOwnerId()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.status").value(ErrorCode.STORE_NOT_FOUND.getHttpStatus().value()))
                    .andExpect(jsonPath("$.data").doesNotExist());
        }
    }
}