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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 용도별 타임아웃 3종 + 각 용도 전용 커넥션 풀(벌크헤드)을 가진 RestClient 구성.
 *
 * <p>기존 RestTemplate(SimpleClientHttpRequestFactory, 풀 없음)을 RestClient + Apache HttpClient5 풀로 전환한
 * 것. 타임아웃 값(connect/read)과 빈 의미(read/write/recovery)는 그대로 보존한다.
 *
 * <p>모든 ACL 호출이 monolith 단일 호스트(=단일 route)로 가므로 maxPerRoute가 사실상 전체 처리량을 좌우한다 → maxPerRoute ≈
 * maxTotal로 산정한다.
 *
 * <p>타임아웃은 풀(ConnectionConfig.socketTimeout / RequestConfig.responseTimeout, connectTimeout)에 이식해
 * 기존 동작을 보존한다. CRT(connectionRequestTimeout)는 풀에서 커넥션을 못 빌릴 때의 대기 한도로, 각 용도의 타임아웃 철학과 정합되게
 * 설정한다(write는 fail-fast 0.5s).
 */
@Configuration
public class RestClientConfig {

  // --- 기존 타임아웃 (변경 없음) ---
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

  // --- 풀: slack (외부 webhook 전용, monolith read 풀과 격리) ---
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

  /**
   * 커넥션 풀 ON/OFF 토글 (부하테스트 A/B 비교용). - true(기본): Apache HttpClient5 풀 사용 (운영 기본값) - false: 풀 적용 전
   * 동작(SimpleClientHttpRequestFactory) 재현 → 풀 효과 비교 측정용
   */
  @Value("${rest-template-pool.enabled:true}")
  private boolean poolEnabled;

  /**
   * 용도별 전용 풀 위에 RestClient를 만든다.
   *
   * @param connectMs 연결 수립 타임아웃 (기존 connect-timeout)
   * @param readMs 소켓/응답 타임아웃 (기존 read-timeout)
   * @param crtMs 풀에서 연결 빌리기 대기 한도 (connectionRequestTimeout)
   * @param maxPerRoute route(=scheme+host+port)당 연결 상한
   * @param maxTotal 풀 전체 연결 상한
   */
  private RestClient build(int connectMs, int readMs, int crtMs, int maxPerRoute, int maxTotal) {
    // [A/B] 풀 OFF: 풀 적용 전 동작(SimpleClientHttpRequestFactory) 재현 — 타임아웃만 보존
    if (!poolEnabled) {
      SimpleClientHttpRequestFactory simple = new SimpleClientHttpRequestFactory();
      simple.setConnectTimeout(connectMs);
      simple.setReadTimeout(readMs);
      return RestClient.builder().requestFactory(simple).build();
    }

    // 1) 풀(ConnectionManager): route당/전체 연결 상한 + 죽은 연결 검증 + connect/socket 타임아웃
    ConnectionConfig connectionConfig =
        ConnectionConfig.custom()
            .setConnectTimeout(Timeout.ofMilliseconds(connectMs))
            .setSocketTimeout(Timeout.ofMilliseconds(readMs)) // ← 기존 read-timeout 보존
            .setValidateAfterInactivity(TimeValue.ofMilliseconds(validateAfterInactivity))
            .setTimeToLive(TimeValue.ofMilliseconds(timeToLive))
            .build();

    PoolingHttpClientConnectionManager connectionManager =
        PoolingHttpClientConnectionManagerBuilder.create()
            .setMaxConnPerRoute(maxPerRoute)
            .setMaxConnTotal(maxTotal)
            .setDefaultConnectionConfig(connectionConfig)
            .build();

    // 2) 요청 단위: 풀 대기 한도(CRT) + 응답 타임아웃(read와 동일 — 이중 안전망)
    RequestConfig requestConfig =
        RequestConfig.custom()
            .setConnectionRequestTimeout(Timeout.ofMilliseconds(crtMs))
            .setResponseTimeout(Timeout.ofMilliseconds(readMs)) // ← read-timeout 보존
            .build();

    CloseableHttpClient httpClient =
        HttpClients.custom()
            .setConnectionManager(connectionManager)
            .setDefaultRequestConfig(requestConfig)
            .evictIdleConnections(TimeValue.ofMilliseconds(evictIdle))
            .evictExpiredConnections()
            .build();

    return RestClient.builder()
        .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
        .build();
  }

  /** 기본 RestClient - 읽기 작업용 (connect 3s / read 5s) + read 풀 */
  @Bean
  @Primary
  public RestClient restClient() {
    return build(connectTimeout, readTimeout, readCrt, readMaxPerRoute, readMaxTotal);
  }

  /** 쓰기 작업용 - Fail-Fast (2s/3s) + write 전용 풀(격리), CRT 500ms */
  @Bean("writeRestClient")
  public RestClient writeRestClient() {
    return build(writeConnectTimeout, writeReadTimeout, writeCrt, writeMaxPerRoute, writeMaxTotal);
  }

  /** 복구 작업용 - 여유 타임아웃 (5s/10s) + recovery 전용 풀(격리) */
  @Bean("recoveryRestClient")
  public RestClient recoveryRestClient() {
    return build(
        recoveryConnectTimeout,
        recoveryReadTimeout,
        recoveryCrt,
        recoveryMaxPerRoute,
        recoveryMaxTotal);
  }

  /**
   * Slack webhook 전용 - 외부 호스트(hooks.slack.com)는 monolith와 다른 route. monolith read 풀(maxTotal)을 외부
   * 호출이 깎아먹지 않도록 작은 전용 풀로 격리. 짧은 타임아웃(2s/3s)으로 알림이 본 로직을 지연시키지 않게 한다.
   */
  @Bean("slackRestClient")
  public RestClient slackRestClient() {
    return build(slackConnectTimeout, slackReadTimeout, slackCrt, slackMaxPerRoute, slackMaxTotal);
  }
}
