package com.ssafy.keeping.qr.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableJpaAuditing
// ⚠️ 스캔 범위를 명시적으로 제한하고 있다 (Redis 리포지토리와 섞이지 않게 하려는 의도).
//    새 JPA 리포지토리를 만들면 **여기에 패키지를 추가해야 한다.**
//    빠뜨리면 컴파일은 되고 기동에서만 죽는다:
//      Parameter N of constructor ... required a bean of type '...Repository' that could not be found
//    (2026-08-24 인프라 수정 — result.md 결함 13. outbox 패키지 누락으로 qr-service 기동 실패)
@EnableJpaRepositories(
    basePackages = {
      "com.ssafy.keeping.qr.domain.intent.repository",
      "com.ssafy.keeping.qr.domain.intent.outbox",
      "com.ssafy.keeping.qr.domain.idempotency.repository"
    })
public class JpaConfig {

  @Bean
  public Clock clock() {
    return Clock.systemDefaultZone();
  }
}
