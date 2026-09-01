package com.ssafy.keeping.qr.domain.qr.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ssafy.keeping.qr.domain.qr.model.QrToken;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * QrToken 직렬화/역직렬화 왕복 테스트.
 *
 * <p>이 버그는 Redis 왕복을 거쳐야만 드러나는 종류다 — isExpired()가 getter 모양이라
 * Jackson이 "expired" 프로퍼티로 인식하고, 역직렬화 시 UnrecognizedPropertyException이 터진다.
 *
 * <p>// 무인 모드에서 미실행 — 복귀 후 최초 실행 필요
 */
class QrTokenSerializationTest {

  /** @Primary ObjectMapper와 동일한 설정 (FAIL_ON_UNKNOWN_PROPERTIES = true) */
  private ObjectMapper primaryMapper;

  /** redisObjectMapper와 동일한 설정 (FAIL_ON_UNKNOWN_PROPERTIES = false) */
  private ObjectMapper redisMapper;

  private QrToken sampleToken;

  @BeforeEach
  void setUp() {
    primaryMapper = new ObjectMapper();
    primaryMapper.registerModule(new JavaTimeModule());
    primaryMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    // FAIL_ON_UNKNOWN_PROPERTIES 기본값 = true

    redisMapper = new ObjectMapper();
    redisMapper.registerModule(new JavaTimeModule());
    redisMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    redisMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    sampleToken =
        QrToken.builder()
            .tokenId("test-token-123")
            .walletId(1L)
            .customerId(5L)
            .bindStoreId(10L)
            .createdAt(LocalDateTime.of(2026, 8, 21, 14, 30, 0))
            .expiresAt(LocalDateTime.of(2026, 8, 21, 14, 30, 10))
            .ttl(10L)
            .build();
  }

  @Nested
  @DisplayName("S-1: save → consumeToken 왕복")
  class RoundTrip {

    @Test
    @DisplayName("redisMapper로 직렬화 → 역직렬화 시 모든 필드가 동일하게 복원된다")
    void roundTrip_with_redisMapper_restores_all_fields() throws Exception {
      String json = redisMapper.writeValueAsString(sampleToken);
      QrToken restored = redisMapper.readValue(json, QrToken.class);

      assertThat(restored.getTokenId()).isEqualTo("test-token-123");
      assertThat(restored.getWalletId()).isEqualTo(1L);
      assertThat(restored.getCustomerId()).isEqualTo(5L);
      assertThat(restored.getBindStoreId()).isEqualTo(10L);
      assertThat(restored.getTtl()).isEqualTo(10L);
      assertThat(restored.getCreatedAt()).isEqualTo(sampleToken.getCreatedAt());
      assertThat(restored.getExpiresAt()).isEqualTo(sampleToken.getExpiresAt());
    }

    @Test
    @DisplayName("primaryMapper로도 왕복이 성공한다 (@JsonIgnore 덕분)")
    void roundTrip_with_primaryMapper_succeeds_after_fix() throws Exception {
      String json = primaryMapper.writeValueAsString(sampleToken);
      QrToken restored = primaryMapper.readValue(json, QrToken.class);

      assertThat(restored.getTokenId()).isEqualTo("test-token-123");
      assertThat(restored.getWalletId()).isEqualTo(1L);
    }
  }

  @Nested
  @DisplayName("S-2: 저장된 JSON에 expired 키가 없음")
  class NoExpiredKey {

    @Test
    @DisplayName("직렬화된 JSON에 'expired' 키가 존재하지 않는다")
    void serialized_json_does_not_contain_expired() throws Exception {
      String json = redisMapper.writeValueAsString(sampleToken);
      JsonNode tree = redisMapper.readTree(json);

      assertThat(tree.has("expired")).isFalse();
      // 정상 필드는 존재
      assertThat(tree.has("tokenId")).isTrue();
      assertThat(tree.has("expiresAt")).isTrue();
    }

    @Test
    @DisplayName("primaryMapper로 직렬화해도 'expired' 키가 없다")
    void primary_mapper_also_excludes_expired() throws Exception {
      String json = primaryMapper.writeValueAsString(sampleToken);
      JsonNode tree = primaryMapper.readTree(json);

      assertThat(tree.has("expired")).isFalse();
    }
  }

  @Nested
  @DisplayName("S-3: 구버전 JSON(expired 포함) 역직렬화")
  class LegacyJsonWithExpired {

    @Test
    @DisplayName("redisMapper: expired가 섞인 구버전 JSON을 예외 없이 읽는다")
    void redisMapper_reads_legacy_json_with_expired() throws Exception {
      String legacyJson =
          """
          {
            "tokenId": "old-token",
            "walletId": 1,
            "customerId": 5,
            "bindStoreId": 10,
            "createdAt": "2026-08-21T14:30:00",
            "expiresAt": "2026-08-21T14:30:10",
            "ttl": 10,
            "expired": false
          }
          """;

      assertThatCode(() -> redisMapper.readValue(legacyJson, QrToken.class))
          .doesNotThrowAnyException();

      QrToken token = redisMapper.readValue(legacyJson, QrToken.class);
      assertThat(token.getTokenId()).isEqualTo("old-token");
      assertThat(token.getWalletId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("primaryMapper도 @JsonIgnoreProperties 덕분에 구버전 JSON을 읽는다")
    void primaryMapper_reads_legacy_json_thanks_to_class_annotation() throws Exception {
      String legacyJson =
          """
          {
            "tokenId": "old-token",
            "walletId": 1,
            "customerId": 5,
            "bindStoreId": 10,
            "createdAt": "2026-08-21T14:30:00",
            "expiresAt": "2026-08-21T14:30:10",
            "ttl": 10,
            "expired": false
          }
          """;

      assertThatCode(() -> primaryMapper.readValue(legacyJson, QrToken.class))
          .doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("S-4, S-5: findByTokenId / findByWalletId 왕복")
  class FindRoundTrip {

    @Test
    @DisplayName("S-4: 직렬화 후 역직렬화 — findByTokenId 경로와 동일")
    void findByTokenId_roundTrip() throws Exception {
      // save와 동일한 경로: writeValueAsString → readValue
      String json = redisMapper.writeValueAsString(sampleToken);
      QrToken found = redisMapper.readValue(json, QrToken.class);

      assertThat(found.getTokenId()).isEqualTo(sampleToken.getTokenId());
      assertThat(found.getWalletId()).isEqualTo(sampleToken.getWalletId());
      assertThat(found.getCustomerId()).isEqualTo(sampleToken.getCustomerId());
      assertThat(found.getBindStoreId()).isEqualTo(sampleToken.getBindStoreId());
    }

    @Test
    @DisplayName("S-5: walletId 기반 조회도 동일한 역직렬화 경로를 탄다")
    void findByWalletId_uses_same_deserialization() throws Exception {
      // findByWalletId는 내부적으로 findByTokenId를 호출하므로 동일한 readValue 경로
      String json = redisMapper.writeValueAsString(sampleToken);
      QrToken found = redisMapper.readValue(json, QrToken.class);

      assertThat(found.getWalletId()).isEqualTo(1L);
    }
  }

  @Nested
  @DisplayName("S-8: LocalDateTime 왕복 보존")
  class LocalDateTimePreservation {

    @Test
    @DisplayName("createdAt/expiresAt이 왕복 후 정확히 보존된다 (JavaTimeModule 회귀 방어)")
    void localDateTime_preserved_after_roundTrip() throws Exception {
      String json = redisMapper.writeValueAsString(sampleToken);
      QrToken restored = redisMapper.readValue(json, QrToken.class);

      assertThat(restored.getCreatedAt())
          .isEqualTo(LocalDateTime.of(2026, 8, 21, 14, 30, 0));
      assertThat(restored.getExpiresAt())
          .isEqualTo(LocalDateTime.of(2026, 8, 21, 14, 30, 10));
    }

    @Test
    @DisplayName("primaryMapper에서도 LocalDateTime이 ISO 문자열로 직렬화된다 (타임스탬프 아님)")
    void localDateTime_serialized_as_iso_string() throws Exception {
      String json = primaryMapper.writeValueAsString(sampleToken);

      assertThat(json).contains("2026-08-21T14:30:00");
      assertThat(json).doesNotContain("1756"); // epoch 밀리초가 아닌 ISO 문자열
    }
  }
}
