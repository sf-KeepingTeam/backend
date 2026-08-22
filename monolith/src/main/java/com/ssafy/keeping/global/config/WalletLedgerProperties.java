package com.ssafy.keeping.global.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 지갑 원장(Wallet-Ledger) 관련 설정 프로퍼티. */
@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "wallet")
public class WalletLedgerProperties {

  private Expiry expiry = new Expiry();
  private Capture capture = new Capture();
  private Reconcile reconcile = new Reconcile();

  @Getter
  @Setter
  public static class Expiry {
    private boolean lazyEnabled = true;
    private int batchSize = 500;
    private int maxGroupsPerRun = 200;
    private String cron = "0 10 3 * * *";
  }

  @Getter
  @Setter
  public static class Capture {
    private boolean strictLot = false;
  }

  @Getter
  @Setter
  public static class Reconcile {
    private String cron = "0 40 3 * * *";
    private int pageSize = 1000;
  }
}
