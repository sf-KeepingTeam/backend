package com.ssafy.keeping.qr.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.time.Clock;

@Configuration
@EnableJpaAuditing
@EnableJpaRepositories(basePackages = {
        "com.ssafy.keeping.qr.domain.intent.repository",
        "com.ssafy.keeping.qr.domain.idempotency.repository",
        "com.ssafy.keeping.qr.saga.repository"
})
public class JpaConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
