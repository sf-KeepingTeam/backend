package com.ssafy.keeping.domain.payment.transactions.repository;

import com.ssafy.keeping.domain.payment.transactions.model.Transaction;
import com.ssafy.keeping.domain.payment.transactions.constant.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    /**
     * transactionUniqueNo로 거래 조회
     */
    Optional<Transaction> findByTransactionUniqueNo(String transactionUniqueNo);

    // ===== 개인지갑 관련 메서드들 (SettlementTask JOIN으로 취소되지 않은 거래만) =====


    /**
     * 개인지갑 - 특정 가게의 유효한 거래내역 조회 (페이징)
     */
    @Query("""
        SELECT t FROM Transaction t
        LEFT JOIN SettlementTask st ON st.transaction = t
        WHERE t.wallet.customer.customerId = :customerId
        AND t.wallet.walletType = 'INDIVIDUAL'
        AND t.store.storeId = :storeId
        AND (st.status IS NULL OR st.status != 'CANCELED')
        ORDER BY t.createdAt DESC
        """)
    Page<Transaction> findValidTransactionsByCustomerAndStore(@Param("customerId") Long customerId,
                                                             @Param("storeId") Long storeId,
                                                             Pageable pageable);

    // ===== 모임지갑 관련 메서드들 (SettlementTask JOIN으로 취소되지 않은 거래만) =====


    /**
     * 모임지갑 - 특정 가게의 유효한 거래내역 조회 (페이징)
     */
    @Query("""
        SELECT t FROM Transaction t
        LEFT JOIN SettlementTask st ON st.transaction = t
        WHERE t.wallet.group.groupId = :groupId
        AND t.wallet.walletType = 'GROUP'
        AND t.store.storeId = :storeId
        AND (st.status IS NULL OR st.status != 'CANCELED')
        ORDER BY t.createdAt DESC
        """)
    Page<Transaction> findValidTransactionsByGroupAndStore(@Param("groupId") Long groupId,
                                                          @Param("storeId") Long storeId,
                                                          Pageable pageable);

    // ============== 통계용 쿼리 메서드들 ==============

    /**
     * 가게별 전체 누적 실제 결제금액 (SettlementTask 기준)
     */
    @Query("SELECT COALESCE(SUM(st.actualPaymentAmount), 0) " +
           "FROM SettlementTask st " +
           "JOIN st.transaction t " +
           "WHERE t.store.storeId = :storeId " +
           "AND t.transactionType = 'CHARGE'")
    Long getTotalPaymentAmountByStore(@Param("storeId") Long storeId);

    /**
     * 가게별 전체 누적 총 충전 포인트 금액 (보너스 포함)
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0) " +
           "FROM Transaction t " +
           "WHERE t.store.storeId = :storeId " +
           "AND t.transactionType = 'CHARGE'")
    Long getTotalChargePointsByStore(@Param("storeId") Long storeId);

    /**
     * 가게별 전체 누적 포인트 사용량
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0) " +
           "FROM Transaction t " +
           "WHERE t.store.storeId = :storeId " +
           "AND t.transactionType = 'USE'")
    Long getTotalPointsUsedByStore(@Param("storeId") Long storeId);

    /**
     * 가게별 전체 거래 건수
     */
    @Query("SELECT COUNT(t) " +
           "FROM Transaction t " +
           "WHERE t.store.storeId = :storeId")
    Long getTotalTransactionCountByStore(@Param("storeId") Long storeId);

    /**
     * 가게별 전체 충전 건수
     */
    @Query("SELECT COUNT(t) " +
           "FROM Transaction t " +
           "WHERE t.store.storeId = :storeId " +
           "AND t.transactionType = 'CHARGE'")
    Long getTotalChargeCountByStore(@Param("storeId") Long storeId);

    /**
     * 가게별 전체 사용 건수
     */
    @Query("SELECT COUNT(t) " +
           "FROM Transaction t " +
           "WHERE t.store.storeId = :storeId " +
           "AND t.transactionType = 'USE'")
    Long getTotalUseCountByStore(@Param("storeId") Long storeId);

    /**
     * 가게별 특정 날짜 실제 결제금액
     */
    @Query("SELECT COALESCE(SUM(st.actualPaymentAmount), 0) " +
           "FROM SettlementTask st " +
           "JOIN st.transaction t " +
           "WHERE t.store.storeId = :storeId " +
           "AND t.transactionType = 'CHARGE' " +
           "AND DATE(t.createdAt) = :date")
    Long getDailyPaymentAmountByStore(@Param("storeId") Long storeId, @Param("date") LocalDate date);

    /**
     * 가게별 특정 날짜 총 충전 포인트 금액 (보너스 포함)
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0) " +
           "FROM Transaction t " +
           "WHERE t.store.storeId = :storeId " +
           "AND t.transactionType = 'CHARGE' " +
           "AND DATE(t.createdAt) = :date")
    Long getDailyTotalChargePointsByStore(@Param("storeId") Long storeId, @Param("date") LocalDate date);

    /**
     * 가게별 특정 날짜 포인트 사용량
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0) " +
           "FROM Transaction t " +
           "WHERE t.store.storeId = :storeId " +
           "AND t.transactionType = 'USE' " +
           "AND DATE(t.createdAt) = :date")
    Long getDailyPointsUsedByStore(@Param("storeId") Long storeId, @Param("date") LocalDate date);

    /**
     * 가게별 특정 날짜 충전 건수
     */
    @Query("SELECT COUNT(t) " +
           "FROM Transaction t " +
           "WHERE t.store.storeId = :storeId " +
           "AND t.transactionType = 'CHARGE' " +
           "AND DATE(t.createdAt) = :date")
    Long getDailyChargeCountByStore(@Param("storeId") Long storeId, @Param("date") LocalDate date);

    /**
     * 가게별 특정 날짜 사용 건수
     */
    @Query("SELECT COUNT(t) " +
           "FROM Transaction t " +
           "WHERE t.store.storeId = :storeId " +
           "AND t.transactionType = 'USE' " +
           "AND DATE(t.createdAt) = :date")
    Long getDailyUseCountByStore(@Param("storeId") Long storeId, @Param("date") LocalDate date);

    /**
     * 가게별 특정 날짜 전체 거래 건수 (충전, 취소, 사용, 회수 포함)
     */
    @Query("SELECT COUNT(t) " +
           "FROM Transaction t " +
           "WHERE t.store.storeId = :storeId " +
           "AND DATE(t.createdAt) = :date")
    Long getDailyTransactionCountByStore(@Param("storeId") Long storeId, @Param("date") LocalDate date);

    /**
     * 가게별 기간별 실제 결제금액
     */
    @Query("SELECT COALESCE(SUM(st.actualPaymentAmount), 0) " +
           "FROM SettlementTask st " +
           "JOIN st.transaction t " +
           "WHERE t.store.storeId = :storeId " +
           "AND t.transactionType = 'CHARGE' " +
           "AND DATE(t.createdAt) BETWEEN :startDate AND :endDate")
    Long getPeriodPaymentAmountByStore(@Param("storeId") Long storeId,
                                       @Param("startDate") LocalDate startDate,
                                       @Param("endDate") LocalDate endDate);

    /**
     * 가게별 기간별 총 충전 포인트 금액 (보너스 포함)
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0) " +
           "FROM Transaction t " +
           "WHERE t.store.storeId = :storeId " +
           "AND t.transactionType = 'CHARGE' " +
           "AND DATE(t.createdAt) BETWEEN :startDate AND :endDate")
    Long getPeriodTotalChargePointsByStore(@Param("storeId") Long storeId,
                                           @Param("startDate") LocalDate startDate,
                                           @Param("endDate") LocalDate endDate);

    /**
     * 가게별 기간별 포인트 사용량
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0) " +
           "FROM Transaction t " +
           "WHERE t.store.storeId = :storeId " +
           "AND t.transactionType = 'USE' " +
           "AND DATE(t.createdAt) BETWEEN :startDate AND :endDate")
    Long getPeriodPointsUsedByStore(@Param("storeId") Long storeId,
                                    @Param("startDate") LocalDate startDate,
                                    @Param("endDate") LocalDate endDate);

    /**
     * 가게별 기간별 충전 건수
     */
    @Query("SELECT COUNT(t) " +
           "FROM Transaction t " +
           "WHERE t.store.storeId = :storeId " +
           "AND t.transactionType = 'CHARGE' " +
           "AND DATE(t.createdAt) BETWEEN :startDate AND :endDate")
    Long getPeriodChargeCountByStore(@Param("storeId") Long storeId,
                                     @Param("startDate") LocalDate startDate,
                                     @Param("endDate") LocalDate endDate);

    /**
     * 가게별 기간별 사용 건수
     */
    @Query("SELECT COUNT(t) " +
           "FROM Transaction t " +
           "WHERE t.store.storeId = :storeId " +
           "AND t.transactionType = 'USE' " +
           "AND DATE(t.createdAt) BETWEEN :startDate AND :endDate")
    Long getPeriodUseCountByStore(@Param("storeId") Long storeId,
                                  @Param("startDate") LocalDate startDate,
                                  @Param("endDate") LocalDate endDate);

    /**
     * 가게별 기간별 전체 거래 건수 (충전, 취소, 사용, 회수 포함)
     */
    @Query("SELECT COUNT(t) " +
           "FROM Transaction t " +
           "WHERE t.store.storeId = :storeId " +
           "AND DATE(t.createdAt) BETWEEN :startDate AND :endDate")
    Long getPeriodTransactionCountByStore(@Param("storeId") Long storeId,
                                          @Param("startDate") LocalDate startDate,
                                          @Param("endDate") LocalDate endDate);

    // ============== 월별 통계용 쿼리 메서드들 ==============

    /**
     * 가게별 특정 연월 실제 결제금액
     */
    @Query("SELECT COALESCE(SUM(st.actualPaymentAmount), 0) " +
           "FROM SettlementTask st " +
           "JOIN st.transaction t " +
           "WHERE t.store.storeId = :storeId " +
           "AND t.transactionType = 'CHARGE' " +
           "AND YEAR(t.createdAt) = :year " +
           "AND MONTH(t.createdAt) = :month")
    Long getMonthlyPaymentAmountByStore(@Param("storeId") Long storeId,
                                        @Param("year") int year,
                                        @Param("month") int month);

    /**
     * 가게별 특정 연월 총 충전 포인트 금액 (보너스 포함)
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0) " +
           "FROM Transaction t " +
           "WHERE t.store.storeId = :storeId " +
           "AND t.transactionType = 'CHARGE' " +
           "AND YEAR(t.createdAt) = :year " +
           "AND MONTH(t.createdAt) = :month")
    Long getMonthlyTotalChargePointsByStore(@Param("storeId") Long storeId,
                                            @Param("year") int year,
                                            @Param("month") int month);

    /**
     * 가게별 특정 연월 포인트 사용량
     */
    @Query("SELECT COALESCE(SUM(t.amount), 0) " +
           "FROM Transaction t " +
           "WHERE t.store.storeId = :storeId " +
           "AND t.transactionType = 'USE' " +
           "AND YEAR(t.createdAt) = :year " +
           "AND MONTH(t.createdAt) = :month")
    Long getMonthlyPointsUsedByStore(@Param("storeId") Long storeId,
                                     @Param("year") int year,
                                     @Param("month") int month);

    /**
     * 가게별 특정 연월 충전 건수
     */
    @Query("SELECT COUNT(t) " +
           "FROM Transaction t " +
           "WHERE t.store.storeId = :storeId " +
           "AND t.transactionType = 'CHARGE' " +
           "AND YEAR(t.createdAt) = :year " +
           "AND MONTH(t.createdAt) = :month")
    Long getMonthlyChargeCountByStore(@Param("storeId") Long storeId,
                                      @Param("year") int year,
                                      @Param("month") int month);

    /**
     * 가게별 특정 연월 사용 건수
     */
    @Query("SELECT COUNT(t) " +
           "FROM Transaction t " +
           "WHERE t.store.storeId = :storeId " +
           "AND t.transactionType = 'USE' " +
           "AND YEAR(t.createdAt) = :year " +
           "AND MONTH(t.createdAt) = :month")
    Long getMonthlyUseCountByStore(@Param("storeId") Long storeId,
                                   @Param("year") int year,
                                   @Param("month") int month);

    /**
     * 가게별 특정 연월 전체 거래 건수 (충전, 취소, 사용, 회수 포함)
     */
    @Query("SELECT COUNT(t) " +
           "FROM Transaction t " +
           "WHERE t.store.storeId = :storeId " +
           "AND YEAR(t.createdAt) = :year " +
           "AND MONTH(t.createdAt) = :month")
    Long getMonthlyTransactionCountByStore(@Param("storeId") Long storeId,
                                           @Param("year") int year,
                                           @Param("month") int month);

    /**
     * PK로 조회 + 쓰기 락 (SELECT ... FOR UPDATE)
     * - 동시성 하에서 동일 TX를 중복 취소/변경하지 않도록 보호!!!
     * - 락 타임아웃은 상황에 맞게 조절(아래 5초 예시)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
            @QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")
    })
    @Query("select t from Transaction t where t.transactionId = :id")
    Optional<Transaction> findByIdWithLock(@Param("id") Long id);

    /**
     * 이 원거래(refTxId)를 참조하는 특정 타입의 트랜잭션 존재 여부(빠른 존재 체크)
     */
    @Query("""
        select (count(t) > 0)
        from Transaction t
        where t.refTransaction.transactionId = :refTxId
          and t.transactionType = :type
    """)
    boolean existsByRefTxIdAndType(@Param("refTxId") Long refTxId,
                                   @Param("type") TransactionType type);

    /**
     * 이 원거래(refTxId)를 참조하는 특정 타입의 트랜잭션 단건 조회(우호적 재생용)
     */
    @Query("""
        select t
        from Transaction t
        where t.refTransaction.transactionId = :refTxId
          and t.transactionType = :type
    """)
    Optional<Transaction> findCancelByRef(@Param("refTxId") Long refTxId,
                                          @Param("type") TransactionType type);
}