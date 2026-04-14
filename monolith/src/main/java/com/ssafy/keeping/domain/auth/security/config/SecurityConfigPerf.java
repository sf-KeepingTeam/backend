package com.ssafy.keeping.domain.auth.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.domain.auth.handler.OAuth2SuccessHandler;
import com.ssafy.keeping.domain.auth.security.JwtAccessDeniedHandler;
import com.ssafy.keeping.domain.auth.security.JwtAuthenticationEntryPoint;
import com.ssafy.keeping.domain.auth.security.filter.JwtAuthenticationFilter;
import com.ssafy.keeping.domain.auth.security.filter.NoStoreAuthResponseFilter;
import com.ssafy.keeping.domain.auth.security.filter.TestHeaderAuthenticationFilter;
import com.ssafy.keeping.domain.auth.token.AccessTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Profile("perf") // perf에서만 적용
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfigPerf {

    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final JwtAccessDeniedHandler jwtAccessDeniedHandler;

    @Value("${fe.base-url}")
    private String feBaseUrl;

    // 기존 SecurityConfig의 ALLOW_URLS / TEMP_ALLOW_URLS 그대로 가져와도 됨
    public static final String[] ALLOW_URLS = SecurityConfig.ALLOW_URLS;
    public static final String[] TEMP_ALLOW_URLS = SecurityConfig.TEMP_ALLOW_URLS;

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter(
            AccessTokenService accessTokenService,
            ObjectMapper objectMapper
    ) {
        return new JwtAuthenticationFilter(accessTokenService, objectMapper);
    }

    @Bean
    public NoStoreAuthResponseFilter noStoreAuthResponseFilter() {
        return new NoStoreAuthResponseFilter();
    }

    // perf에서만 사용할 “테스트 헤더 인증 필터”
    @Bean
    public TestHeaderAuthenticationFilter testHeaderAuthenticationFilter() {
        return new TestHeaderAuthenticationFilter();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(feBaseUrl));
        config.setAllowedMethods(List.of("GET","POST","PUT","DELETE","PATCH","OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            OAuth2SuccessHandler successHandler,
            JwtAuthenticationFilter jwtFilter,
            NoStoreAuthResponseFilter noStoreFilter,
            TestHeaderAuthenticationFilter testHeaderFilter
    ) throws Exception {

        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(h -> h.disable())
                .formLogin(f -> f.disable())
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                        .accessDeniedHandler(jwtAccessDeniedHandler)
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(ALLOW_URLS).permitAll()
                        .requestMatchers(TEMP_ALLOW_URLS).permitAll()

                        // 역할 기반 인가 규칙도 그대로 유지
                        .requestMatchers("/customers/**").hasRole("CUSTOMER")
                        .requestMatchers("/owners/**").hasRole("OWNER")

                        // prepayment는 authenticated 유지 (테스트 헤더로 인증 통과)
                        .requestMatchers("/api/v1/stores/*/prepayment/**").hasRole("CUSTOMER")

                        .anyRequest().authenticated()
                )
                .oauth2Login(oauth -> oauth.successHandler(successHandler))
                .addFilterBefore(noStoreFilter, SecurityContextHolderFilter.class)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(testHeaderFilter, JwtAuthenticationFilter.class);

        return http.build();
    }
}
