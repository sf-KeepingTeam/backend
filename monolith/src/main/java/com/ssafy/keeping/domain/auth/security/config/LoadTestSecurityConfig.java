package com.ssafy.keeping.domain.auth.security.config;

import com.ssafy.keeping.domain.auth.security.filter.LoadTestAuthenticationFilter;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

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

    /**
     * /auth/dev-login 전용 SecurityFilterChain.
     * loadtest.backdoor.enabled=true 일 때만 등록되므로
     * SecurityConfig.ALLOW_URLS에 상시 등록할 필요 없이 조건부 permit 처리.
     */
    @Bean
    @Order(0)
    public SecurityFilterChain devLoginFilterChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher("/auth/dev-login")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }
}
