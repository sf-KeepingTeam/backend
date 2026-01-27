package com.ssafy.keeping.domain.charge.service;

import com.ssafy.keeping.domain.charge.model.SettlementTask;
import com.ssafy.keeping.domain.charge.repository.SettlementTaskRepository;
import com.ssafy.keeping.domain.payment.transactions.model.Transaction;
import com.ssafy.keeping.domain.payment.transactions.repository.TransactionRepository;
import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.store.repository.StoreRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 정산 스케줄러 (포인트 기반)
 * - 선결제 시 점주에게 즉시 포인트 적립됨
 * - 이 스케줄러는 정산 상태 관리 및 기록 보관 용도로만 사용
 *
 * 간소화: 정산 시스템 비활성화
 */
//@Service
@RequiredArgsConstructor
@Slf4j
public class SettlementScheduler {

    private final SettlementTaskRepository settlementTaskRepository;
    private final TransactionRepository transactionRepository;
    private final StoreRepository storeRepository;

    /**
     * 매주 월요일 오전 07:30에 이전 주 PENDING 작업들을 LOCKED 상태로 변경
     * 취소 불가 상태로 전환 (취소 기간 종료)
     */
    @Scheduled(cron = "0 30 7 * * MON", zone = "Asia/Seoul")
    @Transactional
    public void lockPreviousWeekTasks() {
        log.info("=== 결제취소 차단 스케줄러 시작 ===");

        try {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime thisWeekMondayBilling = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .withHour(7).withMinute(30).withSecond(0).withNano(0);
            LocalDateTime lastWeekMondayBilling = thisWeekMondayBilling.minusWeeks(1);

            log.info("취소 차단 범위: {} ~ {}", lastWeekMondayBilling, thisWeekMondayBilling);

            List<SettlementTask> pendingTasks = settlementTaskRepository
                    .findPendingTasksFromPreviousWeek(lastWeekMondayBilling, thisWeekMondayBilling);

            if (pendingTasks.isEmpty()) {
                log.info("LOCKED로 변경할 작업이 없습니다.");
                return;
            }

            log.info("LOCKED로 변경할 작업 수: {}", pendingTasks.size());

            for (SettlementTask task : pendingTasks) {
                task.markAsLocked();
                settlementTaskRepository.save(task);
            }

            log.info("=== 결제취소 차단 스케줄러 완료 - 변경된 작업 수: {} ===", pendingTasks.size());

        } catch (Exception e) {
            log.error("결제취소 차단 스케줄러 실행 중 오류 발생", e);
        }
    }

    /**
     * 매주 화요일 오전 01:00에 LOCKED 상태 작업들을 COMPLETED로 변경
     * (포인트는 이미 선결제 시 즉시 적립되었으므로, 상태만 변경)
     */
    @Scheduled(cron = "0 0 1 * * TUE", zone = "Asia/Seoul")
    @Transactional
    public void processLockedSettlements() {
        log.info("=== 정산 완료 처리 스케줄러 시작 ===");

        try {
            List<SettlementTask> lockedTasks = settlementTaskRepository.findLockedTasks();

            if (lockedTasks.isEmpty()) {
                log.info("처리할 정산 작업이 없습니다.");
                return;
            }

            log.info("완료 처리 대상 작업 수: {}", lockedTasks.size());

            // transaction IDs 추출 및 조회
            Set<Long> transactionIds = lockedTasks.stream()
                    .map(SettlementTask::getTransactionId)
                    .collect(Collectors.toSet());
            Map<Long, Transaction> transactionMap = transactionRepository.findAllById(transactionIds).stream()
                    .collect(Collectors.toMap(Transaction::getTransactionId, t -> t));

            // 가게별로 그룹화하여 로깅 (storeId 기준)
            Map<Long, List<SettlementTask>> tasksByStoreId = lockedTasks.stream()
                    .collect(Collectors.groupingBy(task -> {
                        Transaction tx = transactionMap.get(task.getTransactionId());
                        return tx != null ? tx.getStoreId() : 0L;
                    }));

            for (Map.Entry<Long, List<SettlementTask>> entry : tasksByStoreId.entrySet()) {
                Long storeId = entry.getKey();
                List<SettlementTask> tasks = entry.getValue();

                Long totalAmount = tasks.stream()
                        .map(SettlementTask::getActualPaymentAmount)
                        .reduce(0L, Long::sum);

                String storeName = storeRepository.findById(storeId)
                        .map(Store::getStoreName)
                        .orElse("Unknown Store");

                log.info("가게별 정산 완료 처리 - 가게: {}, 작업 수: {}, 총 금액: {}",
                        storeName, tasks.size(), totalAmount);

                // 상태만 COMPLETED로 변경 (포인트는 이미 적립됨)
                markTasksAsCompleted(tasks);
            }

            log.info("=== 정산 완료 처리 스케줄러 종료 ===");

        } catch (Exception e) {
            log.error("정산 완료 처리 스케줄러 실행 중 오류 발생", e);
        }
    }

    /**
     * 정산 작업들을 완료 상태로 변경
     */
    private void markTasksAsCompleted(List<SettlementTask> tasks) {
        for (SettlementTask task : tasks) {
            task.markAsCompleted();
            settlementTaskRepository.save(task);
        }
    }
}
