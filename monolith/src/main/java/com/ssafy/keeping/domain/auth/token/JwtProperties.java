package com.ssafy.keeping.domain.auth.token;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.auth.jwt")
public record JwtProperties(
        String issuer,
        String secret,
        long accessTtlSeconds,
        long refreshTtlSeconds
) {}

/*
@ConfigurationProperties(prefix = "app.auth.jwt")
이 어노테이션이 있다고 자동으로 Bean으로 등록되는건 아니고

@Component 어노테이션을 붙이거나
or
SecurityConfig 클래스에 @EnableConfigurationProperties(JwtProperties.class) 이렇게 등록해주어야 한다.
 */