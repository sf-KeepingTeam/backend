package com.ssafy.keeping.domain.auth.security.config;

import com.ssafy.keeping.domain.auth.security.filter.LoadTestAuthenticationFilter;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@ConditionalOnProperty(name = "loadtest.backdoor.enabled", havingValue = "true")
public class LoadTestSecurityConfig {

    @PostConstruct
    public void warnBackdoorEnabled() {
        log.warn("========================================");
        log.warn("WARNING: LoadTest backdoor is ENABLED!");
        log.warn("This should NEVER be used in production.");
        log.warn("========================================");
    }

    @Bean
    public LoadTestAuthenticationFilter loadTestAuthenticationFilter() {
        return new LoadTestAuthenticationFilter();
    }
}
