package com.ssafy.keeping.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = WalletLedgerProperties.class)
@EnableConfigurationProperties(WalletLedgerProperties.class)
@TestPropertySource(
    properties = {
      "wallet.expiry.lazy-enabled=false",
      "wallet.expiry.batch-size=100",
      "wallet.expiry.max-groups-per-run=50",
      "wallet.expiry.cron=0 0 4 * * *",
      "wallet.capture.strict-lot=true",
      "wallet.reconcile.cron=0 0 5 * * *",
      "wallet.reconcile.page-size=500",
    })
class WalletLedgerPropertiesTest {

  @Autowired private WalletLedgerProperties properties;

  @Test
  @DisplayName("expiry 프로퍼티가 올바르게 바인딩된다")
  void expiry_properties_bound() {
    assertThat(properties.getExpiry().isLazyEnabled()).isFalse();
    assertThat(properties.getExpiry().getBatchSize()).isEqualTo(100);
    assertThat(properties.getExpiry().getMaxGroupsPerRun()).isEqualTo(50);
    assertThat(properties.getExpiry().getCron()).isEqualTo("0 0 4 * * *");
  }

  @Test
  @DisplayName("capture 프로퍼티가 올바르게 바인딩된다")
  void capture_properties_bound() {
    assertThat(properties.getCapture().isStrictLot()).isTrue();
  }

  @Test
  @DisplayName("reconcile 프로퍼티가 올바르게 바인딩된다")
  void reconcile_properties_bound() {
    assertThat(properties.getReconcile().getCron()).isEqualTo("0 0 5 * * *");
    assertThat(properties.getReconcile().getPageSize()).isEqualTo(500);
  }

  @Test
  @DisplayName("기본값이 올바르게 설정된다")
  void default_values() {
    WalletLedgerProperties defaults = new WalletLedgerProperties();

    assertThat(defaults.getExpiry().isLazyEnabled()).isTrue();
    assertThat(defaults.getExpiry().getBatchSize()).isEqualTo(500);
    assertThat(defaults.getExpiry().getMaxGroupsPerRun()).isEqualTo(200);
    assertThat(defaults.getExpiry().getCron()).isEqualTo("0 10 3 * * *");
    assertThat(defaults.getCapture().isStrictLot()).isFalse();
    assertThat(defaults.getReconcile().getCron()).isEqualTo("0 40 3 * * *");
    assertThat(defaults.getReconcile().getPageSize()).isEqualTo(1000);
  }
}
