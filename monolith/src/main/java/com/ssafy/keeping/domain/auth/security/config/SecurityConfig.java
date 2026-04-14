package com.ssafy.keeping.domain.auth.security.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.domain.auth.handler.OAuth2SuccessHandler;
import com.ssafy.keeping.domain.auth.security.JwtAccessDeniedHandler;
import com.ssafy.keeping.domain.auth.security.filter.JwtAuthenticationFilter;
import com.ssafy.keeping.domain.auth.security.filter.NoStoreAuthResponseFilter;
import com.ssafy.keeping.domain.auth.security.JwtAuthenticationEntryPoint;
import com.ssafy.keeping.domain.auth.security.filter.LoadTestAuthenticationFilter;
import com.ssafy.keeping.domain.auth.token.AccessTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
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

@Profile("!perf")
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;
    private final JwtAccessDeniedHandler jwtAccessDeniedHandler;

    @Autowired(required = false)
    private LoadTestAuthenticationFilter loadTestAuthenticationFilter;

    @Value("${fe.base-url}")
    private String feBaseUrl;

    public static final String[] ALLOW_URLS = {
            "/auth/refresh",
            "/auth/logout",
            "/signup/**",

            // todo: 아래 경로들이 인증이 필요 없는 부분인지 확인하기
            "/login/**",
            "/oauth2/**",
            "/error",
            "/actuator/health",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            "/swagger-resources/**",
            "/favicon.ico",
            "/.well-known/**",
            "/s3/**",
            "/ocr/*",
            "/debug/redis",
            "/swagger-ui.html",
            "/actuator/**",
            "/loadtest/health",

            // Internal API - 마이크로서비스 간 통신용 (X-Internal-Auth 헤더로 보호)
            "/internal/**"
    };

    public static final String[] TEMP_ALLOW_URLS = {

    };

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

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(feBaseUrl)); // 프론트 도메인 (Origin이 여러개인 경우... CORS가 깨질 수 있음... -> 여러개 등록하거나 yml에서 배열로 관리)
        config.setAllowedMethods(List.of("GET","POST","PUT","DELETE","PATCH","OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true); // 쿠키 포함 허용
        config.setMaxAge(3600L);
//        config.setExposedHeaders(Arrays.asList("Authorization", "X-Total-Count")); // 프론트에서 Authorization 헤더를 응답에서 읽어야 하면 필요.

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            OAuth2SuccessHandler successHandler,
            JwtAuthenticationFilter jwtFilter,
            NoStoreAuthResponseFilter noStoreFilter
    ) throws Exception {

        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(h -> h.disable())
                .formLogin(f -> f.disable())
                .exceptionHandling(eh -> eh
                        .authenticationEntryPoint(jwtAuthenticationEntryPoint) // 인증 실패(401)
                        .accessDeniedHandler(jwtAccessDeniedHandler) // 권한 부족(403)
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(ALLOW_URLS).permitAll()
                        .requestMatchers(TEMP_ALLOW_URLS).permitAll() // 임시용

                        // 역할 기반 인가 테스트
                        // .hasRole() 인증도 필요하고, 권한(Authority)에 ROLE_{}이 있어야 통과
                        .requestMatchers("/loadtest/verify-customer").hasRole("CUSTOMER")
                        .requestMatchers("/loadtest/verify-owner").hasRole("OWNER")
                        .requestMatchers("/customers/**").hasRole("CUSTOMER")
                        .requestMatchers("/owners/**").hasRole("OWNER")
                        // QR 관련 경로는 QR Service에서 처리 (cpqr/*, payments/*/approve)
                        .requestMatchers("/stores/*/transactions/*/refund").hasRole("OWNER")

                        .anyRequest().authenticated() // 위에서 따로 허용해주지 않은 나머지 모든 요청은 “인증 필요”
                )
                .oauth2Login(oauth -> oauth.successHandler(successHandler))
                .addFilterBefore(noStoreFilter, SecurityContextHolderFilter.class)
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        // LoadTest 백도어 필터 추가 (loadtest 프로파일에서만 활성화)
        if (loadTestAuthenticationFilter != null) {
            http.addFilterBefore(loadTestAuthenticationFilter, JwtAuthenticationFilter.class);
        }

        return http.build();
    }

}
