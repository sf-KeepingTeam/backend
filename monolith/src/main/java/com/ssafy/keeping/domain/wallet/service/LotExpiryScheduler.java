package com.ssafy.keeping.domain.wallet.service;

import com.ssafy.keeping.domain.wallet.dto.ExpirySweepReport;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 만료 Lot 스윕 스케줄러. ShedLock으로 다중 인스턴스 중복 방지. */
@Component
@RequiredArgsConstructor
@Slf4j
public class LotExpiryScheduler {

  private final LotExpiryService lotExpiryService;

  @Scheduled(cron = "${wallet.expiry.cron:0 10 3 * * *}", zone = "Asia/Seoul")
  @SchedulerLock(
      name = "lotExpirySweep",
      lockAtMostFor = "PT30M",
      lockAtLeastFor = "PT10S")
  public void scheduledSweep() {
    log.info("[EXPIRY_SCHEDULER] starting lot expiry sweep");
    ExpirySweepReport report = lotExpiryService.sweepOnce(LocalDateTime.now());
    log.info(
        "[EXPIRY_SCHEDULER] completed: settledGroups={} settledLots={} settledAmount={} skippedLocked={} remainingBacklog={}",
        report.settledGroups(),
        report.settledLots(),
        report.settledAmount(),
        report.skippedLocked(),
        report.remainingBacklog());
  }
}
