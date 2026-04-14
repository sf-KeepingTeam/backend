package com.ssafy.keeping.domain.charge.repository;

import com.ssafy.keeping.domain.charge.model.SettlementTask;
import com.ssafy.keeping.domain.payment.transactions.model.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SettlementTaskRepository extends JpaRepository<SettlementTask, Long> {

    /**
     * 이전 주의 PENDING 상태 정산 작업 조회 (월요일 07:30 실행용)
     * 지난 주 월요일 07:30부터 월요일 07:30까지의 PENDING 상태 작업
     */
    @Query("SELECT st FROM SettlementTask st WHERE st.status = 'PENDING' " +
           "AND st.createdAt >= :weekStart AND st.createdAt < :weekEnd ORDER BY st.createdAt ASC")
    List<SettlementTask> findPendingTasksFromPreviousWeek(@Param("weekStart") LocalDateTime weekStart, 
                                                          @Param("weekEnd") LocalDateTime weekEnd);

    /**
     * LOCKED 상태의 정산 작업 조회 (화요일 01:00 실행용)
     */
    @Query("SELECT st FROM SettlementTask st WHERE st.status = 'LOCKED' ORDER BY st.createdAt ASC")
    List<SettlementTask> findLockedTasks();

    /**
     * 특정 고객의 취소 가능한 거래 목록 조회 (페이지네이션)
     * 조건: 1) PENDING 상태, 2) CHARGE 타입 wallet_store_lot, 3) 미사용 포인트 (총량=잔액)
     */
    @Query("SELECT st FROM SettlementTask st " +
           "JOIN st.transaction t " +
           "JOIN WalletStoreLot wsl ON wsl.originChargeTransaction = t " +
           "WHERE st.status = 'PENDING' " +
           "AND t.customer.customerId = :customerId " +
           "AND t.transactionType = 'CHARGE' " +
           "AND wsl.sourceType = 'CHARGE' " +
           "AND wsl.amountTotal = wsl.amountRemaining " +
           "ORDER BY t.createdAt DESC")
    Page<SettlementTask> findCancelableTransactions(@Param("customerId") Long customerId, Pageable pageable);

    /**
     * Transaction으로 SettlementTask 조회
     */
    Optional<SettlementTask> findByTransaction(Transaction transaction);
}