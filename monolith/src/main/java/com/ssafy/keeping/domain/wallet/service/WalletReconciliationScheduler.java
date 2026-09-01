package com.ssafy.keeping.domain.wallet.service;

import com.ssafy.keeping.domain.wallet.dto.ReconcileReport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 지갑 대사(Reconciliation) 스케줄러. ShedLock으로 다중 인스턴스 중복 방지. */
@Component
@RequiredArgsConstructor
@Slf4j
public class WalletReconciliationScheduler {

  private final WalletReconciliationService reconciliationService;

  @Scheduled(cron = "${wallet.reconcile.cron:0 40 3 * * *}", zone = "Asia/Seoul")
  @SchedulerLock(
      name = "walletReconcile",
      lockAtMostFor = "PT1H",
      lockAtLeastFor = "PT10S")
  public void scheduledReconcile() {
    log.info("[RECONCILE_SCHEDULER] starting wallet reconciliation");
    ReconcileReport report = reconciliationService.runOnce();
    log.info(
        "[RECONCILE_SCHEDULER] completed: scannedPairs={} mismatchCount={} balanceOverLotSum={} balanceUnderLotSum={}",
        report.scannedPairs(),
        report.mismatchCount(),
        report.balanceOverLotSum(),
        report.balanceUnderLotSum());
  }
}
