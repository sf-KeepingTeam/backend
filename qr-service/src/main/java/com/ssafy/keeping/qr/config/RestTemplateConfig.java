package com.ssafy.keeping.qr.config;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 용도별 타임아웃 3종 + 각 용도 전용 커넥션 풀(벌크헤드)을 가진 RestTemplate 구성.
 *
 * <p>Apache HttpClient5 {@link PoolingHttpClientConnectionManager}를 사용하여 TCP 연결을 재활용한다. 모든 ACL 호출이
 * monolith 단일 호스트(=단일 route)로 가므로 maxPerRoute ≈ maxTotal로 산정.
 *
 * <p>타임아웃은 HttpClient5의 {@link ConnectionConfig}/{@link RequestConfig}에서만 설정한다. {@link
 * RestTemplateBuilder}는 Micrometer Tracing(TraceId 전파)을 위해 유지하되 {@code requestFactory(() ->
 * factory)} 형태로 factory만 교체한다.
 */
@Configuration
public class RestTemplateConfig {

  // --- 타임아웃 ---
  @Value("${rest-template.connect-timeout:3000}")
  private int connectTimeout;

  @Value("${rest-template.read-timeout:5000}")
  private int readTimeout;

  @Value("${rest-template-write.connect-timeout:2000}")
  private int writeConnectTimeout;

  @Value("${rest-template-write.read-timeout:3000}")
  private int writeReadTimeout;

  @Value("${rest-template-recovery.connect-timeout:5000}")
  private int recoveryConnectTimeout;

  @Value("${rest-template-recovery.read-timeout:10000}")
  private int recoveryReadTimeout;

  // --- 풀: read ---
  @Value("${rest-template-pool.read.max-per-route:50}")
  private int readMaxPerRoute;

  @Value("${rest-template-pool.read.max-total:60}")
  private int readMaxTotal;

  @Value("${rest-template-pool.read.connection-request-timeout:2000}")
  private int readCrt;

  // --- 풀: write ---
  @Value("${rest-template-pool.write.max-per-route:30}")
  private int writeMaxPerRoute;

  @Value("${rest-template-pool.write.max-total:30}")
  private int writeMaxTotal;

  @Value("${rest-template-pool.write.connection-request-timeout:500}")
  private int writeCrt;

  // --- 풀: recovery ---
  @Value("${rest-template-pool.recovery.max-per-route:10}")
  private int recoveryMaxPerRoute;

  @Value("${rest-template-pool.recovery.max-total:10}")
  private int recoveryMaxTotal;

  @Value("${rest-template-pool.recovery.connection-request-timeout:2000}")
  private int recoveryCrt;

  // --- 풀: slack ---
  @Value("${rest-template-pool.slack.connect-timeout:2000}")
  private int slackConnectTimeout;

  @Value("${rest-template-pool.slack.read-timeout:3000}")
  private int slackReadTimeout;

  @Value("${rest-template-pool.slack.max-per-route:5}")
  private int slackMaxPerRoute;

  @Value("${rest-template-pool.slack.max-total:5}")
  private int slackMaxTotal;

  @Value("${rest-template-pool.slack.connection-request-timeout:500}")
  private int slackCrt;

  // --- 풀 공통 ---
  @Value("${rest-template-pool.validate-after-inactivity:5000}")
  private int validateAfterInactivity;

  @Value("${rest-template-pool.evict-idle:30000}")
  private int evictIdle;

  @Value("${rest-template-pool.time-to-live:300000}")
  private int timeToLive;

  /** 풀 ON/OFF 토글 (부하테스트 A/B 비교용). false → SimpleClientHttpRequestFactory 폴백 */
  @Value("${rest-template-pool.enabled:true}")
  private boolean poolEnabled;

  /**
   * 풀링된 HttpClient5 기반 RestTemplate을 생성한다. builder를 통과시켜 Micrometer
   * ObservationRestTemplateCustomizer가 적용되도록 보장한다.
   */
  private RestTemplate buildPooled(
      RestTemplateBuilder builder,
      int connectMs,
      int readMs,
      int crtMs,
      int maxPerRoute,
      int maxTotal) {
    if (!poolEnabled) {
      SimpleClientHttpRequestFactory simple = new SimpleClientHttpRequestFactory();
      simple.setConnectTimeout(connectMs);
      simple.setReadTimeout(readMs);
      return builder.requestFactory(() -> simple).build();
    }

    ConnectionConfig connectionConfig =
        ConnectionConfig.custom()
            .setConnectTimeout(Timeout.ofMilliseconds(connectMs))
            .setSocketTimeout(Timeout.ofMilliseconds(readMs))
            .setValidateAfterInactivity(TimeValue.ofMilliseconds(validateAfterInactivity))
            .setTimeToLive(TimeValue.ofMilliseconds(timeToLive))
            .build();

    PoolingHttpClientConnectionManager connManager =
        PoolingHttpClientConnectionManagerBuilder.create()
            .setMaxConnPerRoute(maxPerRoute)
            .setMaxConnTotal(maxTotal)
            .setDefaultConnectionConfig(connectionConfig)
            .build();

    RequestConfig requestConfig =
        RequestConfig.custom()
            .setConnectionRequestTimeout(Timeout.ofMilliseconds(crtMs))
            .setResponseTimeout(Timeout.ofMilliseconds(readMs))
            .build();

    CloseableHttpClient httpClient =
        HttpClients.custom()
            .setConnectionManager(connManager)
            .setDefaultRequestConfig(requestConfig)
            .evictIdleConnections(TimeValue.ofMilliseconds(evictIdle))
            .evictExpiredConnections()
            .build();

    HttpComponentsClientHttpRequestFactory factory =
        new HttpComponentsClientHttpRequestFactory(httpClient);

    return builder.requestFactory(() -> factory).build();
  }

  /** 기본 RestTemplate — 읽기 작업용 (connect 3s / read 5s) + read 풀 */
  @Bean
  @Primary
  public RestTemplate restTemplate(RestTemplateBuilder builder) {
    return buildPooled(
        builder, connectTimeout, readTimeout, readCrt, readMaxPerRoute, readMaxTotal);
  }

  /** 쓰기 작업용 — Fail-Fast (2s/3s) + write 전용 풀, CRT 500ms */
  @Bean("writeRestTemplate")
  public RestTemplate writeRestTemplate(RestTemplateBuilder builder) {
    return buildPooled(
        builder, writeConnectTimeout, writeReadTimeout, writeCrt, writeMaxPerRoute, writeMaxTotal);
  }

  /** 복구 작업용 — 여유 타임아웃 (5s/10s) + recovery 전용 풀 */
  @Bean("recoveryRestTemplate")
  public RestTemplate recoveryRestTemplate(RestTemplateBuilder builder) {
    return buildPooled(
        builder,
        recoveryConnectTimeout,
        recoveryReadTimeout,
        recoveryCrt,
        recoveryMaxPerRoute,
        recoveryMaxTotal);
  }

  /** Slack webhook 전용 — 외부 호스트 격리, 짧은 타임아웃 */
  @Bean("slackRestTemplate")
  public RestTemplate slackRestTemplate(RestTemplateBuilder builder) {
    return buildPooled(
        builder, slackConnectTimeout, slackReadTimeout, slackCrt, slackMaxPerRoute, slackMaxTotal);
  }
}
