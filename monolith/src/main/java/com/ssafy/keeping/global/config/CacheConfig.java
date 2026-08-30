package com.ssafy.keeping.global.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

/**
 * 정적 참조 데이터 캐시 설정.
 *
 * <p>CACHE_PROVIDER 환경변수로 런타임 전환 (빌드 1회):
 *
 * <ul>
 *   <li>none (기본) — NoOpCacheManager, 기존 동작과 100% 동일
 *   <li>caffeine — 인메모리, 직렬화 없음. allinone 부하테스트 권장
 *   <li>redis — 분산, GenericJackson2JsonRedisSerializer 사용. JPA 엔티티(Customer/Wallet)
 *       직렬화 시 LAZY 프록시 실패 가능 — 사전 검증 후 사용
 * </ul>
 *
 * <p>AOP 순서: order = LOWEST_PRECEDENCE - 1 → 캐시 어드바이저가 트랜잭션 어드바이저 외곽을 감쌈.
 * 캐시 히트 시 트랜잭션(커넥션 점유) 진입 안 함.
 *
 * <p>캐시 리전:
 *
 * <ul>
 *   <li>store (storeId → StorePublicDto, TTL 300s, max 500)
 *   <li>storeEntity (storeId → Store 엔티티 존재 확인용, TTL 300s, max 500)
 *   <li>menus (storeId → List&lt;MenuResponseDto&gt;, TTL 300s, max 500)
 *   <li>customer (customerId → Customer, TTL 600s, max 2000)
 *   <li>wallet (customerId:walletType → Wallet, TTL 600s, max 2000)
 * </ul>
 *
 * <p>주의: TTL 동안 DB 변경이 캐시에 반영되지 않음. 매장/메뉴 CUD 후 최대 300s 지연 허용.
 */
@Configuration
@EnableCaching(order = Ordered.LOWEST_PRECEDENCE - 1)
@Slf4j
public class CacheConfig {

  @Value("${CACHE_PROVIDER:none}")
  private String cacheProvider;

  @Bean
  public CacheManager cacheManager(RedisConnectionFactory redisConnectionFactory) {
    CacheManager cm =
        switch (cacheProvider.toLowerCase()) {
          case "caffeine" -> caffeineCacheManager();
          case "redis" -> redisCacheManager(redisConnectionFactory);
          default -> new NoOpCacheManager();
        };
    log.info("[Cache] provider={}", cacheProvider);
    return cm;
  }

  private CacheManager caffeineCacheManager() {
    SimpleCacheManager manager = new SimpleCacheManager();
    manager.setCaches(
        List.of(
            caffeineCache("store", Duration.ofSeconds(300), 500),
            caffeineCache("storeEntity", Duration.ofSeconds(300), 500),
            caffeineCache("menus", Duration.ofSeconds(300), 500),
            caffeineCache("customer", Duration.ofSeconds(600), 2_000),
            caffeineCache("wallet", Duration.ofSeconds(600), 2_000)));
    return manager;
  }

  private CaffeineCache caffeineCache(String name, Duration ttl, long maxSize) {
    return new CaffeineCache(
        name, Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(maxSize).build());
  }

  private CacheManager redisCacheManager(RedisConnectionFactory factory) {
    // 주의: JPA 엔티티(Customer, Wallet) Redis 직렬화 시 LAZY 프록시로 실패 가능.
    // redis provider 사용 전 반드시 직렬화 왕복 테스트 수행.
    GenericJackson2JsonRedisSerializer serializer = new GenericJackson2JsonRedisSerializer();
    RedisSerializationContext.SerializationPair<Object> pair =
        RedisSerializationContext.SerializationPair.fromSerializer(serializer);

    RedisCacheConfiguration base =
        RedisCacheConfiguration.defaultCacheConfig().serializeValuesWith(pair);

    return RedisCacheManager.builder(factory)
        .withCacheConfiguration("store", base.entryTtl(Duration.ofSeconds(300)))
        .withCacheConfiguration("storeEntity", base.entryTtl(Duration.ofSeconds(300)))
        .withCacheConfiguration("menus", base.entryTtl(Duration.ofSeconds(300)))
        .withCacheConfiguration("customer", base.entryTtl(Duration.ofSeconds(600)))
        .withCacheConfiguration("wallet", base.entryTtl(Duration.ofSeconds(600)))
        .build();
  }
}
