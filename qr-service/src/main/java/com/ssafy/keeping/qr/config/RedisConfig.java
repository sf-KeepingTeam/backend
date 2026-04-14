package com.ssafy.keeping.qr.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.repository.configuration.EnableRedisRepositories;

/**
 * Redis Repository 설정
 * redisTemplate은 CacheConfig에서 정의
 */
@Configuration
@EnableRedisRepositories(basePackages = "com.ssafy.keeping.qr.domain.qr.repository")
public class RedisConfig {
}
