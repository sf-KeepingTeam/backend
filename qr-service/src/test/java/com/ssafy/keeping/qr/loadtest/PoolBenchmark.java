package com.ssafy.keeping.qr.loadtest;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 커넥션 풀 ON/OFF 부하 벤치마크 (DB 불필요, HTTP 계층만 격리 측정).
 *
 * <p>같은 Spring RestClient 스택을 쓰되, 단 하나의 변수만 바꾼다: <b>연결 재사용 여부</b>.
 *
 * <ul>
 *   <li><b>pooled</b>: PoolingHttpClientConnectionManager + 재사용 ON (실제 적용본과 동일, maxTotal 50). → 소수
 *       연결을 재사용 → TIME_WAIT 거의 없음, 포트 안전.
 *   <li><b>nopool</b>: 재사용 OFF (setConnectionReuseStrategy=false) → 매 요청 새 연결 + 종료. → 클라이언트 측
 *       TIME_WAIT 누적, 고부하 시 포트 고갈(BindException).
 * </ul>
 *
 * <p>풀은 RestClient가 아니라 그 아래 HttpClient 엔진 레벨이라, 이 비교가 곧 우리가 적용한 커넥션 풀의 효과를 그대로 보여준다.
 *
 * <p>실행: ./gradlew loadBench -PappArgs="http://localhost:8080/internal/stores/1 nopool 200 30"
 * ./gradlew loadBench -PappArgs="http://localhost:8080/internal/stores/1 pooled 200 30" 인자: [url]
 * [mode=pooled|nopool] [concurrency] [durationSeconds]
 */
public class PoolBenchmark {

  public static void main(String[] args) throws Exception {
    String url = arg(args, 0, "http://localhost:8080/internal/stores/1");
    String mode = arg(args, 1, "nopool");
    int concurrency = Integer.parseInt(arg(args, 2, "100"));
    int durationSec = Integer.parseInt(arg(args, 3, "30"));

    RestClient client = "pooled".equalsIgnoreCase(mode) ? pooledClient() : noPoolClient();

    System.out.printf(
        "[PoolBenchmark] mode=%s concurrency=%d duration=%ds url=%s%n",
        mode, concurrency, durationSec, url);
    System.out.println("측정: 다른 창에서 TIME_WAIT 소켓 수를 모니터링하세요 (loadtest-howto.md 참고).");

    AtomicLong ok = new AtomicLong();
    AtomicLong err = new AtomicLong();
    ConcurrentHashMap<String, AtomicLong> errTypes = new ConcurrentHashMap<>();

    ExecutorService workers = Executors.newFixedThreadPool(concurrency);
    long startNs = System.nanoTime();
    long endNs = startNs + durationSec * 1_000_000_000L;

    for (int i = 0; i < concurrency; i++) {
      workers.submit(
          () -> {
            while (System.nanoTime() < endNs) {
              try {
                client.get().uri(url).retrieve().body(String.class);
                ok.incrementAndGet();
              } catch (Throwable t) {
                err.incrementAndGet();
                errTypes.computeIfAbsent(rootCause(t), k -> new AtomicLong()).incrementAndGet();
              }
            }
          });
    }
    workers.shutdown();
    workers.awaitTermination(durationSec + 30L, TimeUnit.SECONDS);

    double secs = (System.nanoTime() - startNs) / 1e9;
    long total = ok.get() + err.get();
    System.out.println();
    System.out.println("================ 결과 ================");
    System.out.printf("mode=%s  concurrency=%d  elapsed=%.1fs%n", mode, concurrency, secs);
    System.out.printf(
        "total=%d  ok=%d  err=%d  throughput=%.0f req/s%n",
        total, ok.get(), err.get(), total / secs);
    if (err.get() > 0) {
      System.out.println("에러 분류:");
      errTypes.forEach((k, v) -> System.out.printf("  %-28s %d%n", k, v.get()));
      System.out.println("  ※ BindException / 'Address already in use' = 포트 고갈 신호");
    }
    System.out.println("=====================================");
  }

  /** 실제 적용본과 동일: 풀 ON, maxTotal 50, 연결 재사용 */
  private static RestClient pooledClient() {
    PoolingHttpClientConnectionManager cm =
        PoolingHttpClientConnectionManagerBuilder.create()
            .setMaxConnPerRoute(50)
            .setMaxConnTotal(50)
            .setDefaultConnectionConfig(
                ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.ofMilliseconds(3000))
                    .setSocketTimeout(Timeout.ofMilliseconds(5000))
                    .setValidateAfterInactivity(TimeValue.ofMilliseconds(5000))
                    .setTimeToLive(TimeValue.ofMilliseconds(300000))
                    .build())
            .build();

    CloseableHttpClient httpClient =
        HttpClients.custom()
            .setConnectionManager(cm)
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setConnectionRequestTimeout(Timeout.ofMilliseconds(2000))
                    .setResponseTimeout(Timeout.ofMilliseconds(5000))
                    .build())
            .evictIdleConnections(TimeValue.ofMilliseconds(30000))
            .evictExpiredConnections()
            .build();

    return RestClient.builder()
        .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
        .build();
  }

  /** 비교군: 연결 재사용 OFF → 매 요청 새 TCP 연결 + 종료(클라 TIME_WAIT 누적) */
  private static RestClient noPoolClient() {
    // 풀 자체는 충분히 크게(스로틀 방지) 두고, 재사용만 끔 → 동시성은 유지하되 연결은 churn
    PoolingHttpClientConnectionManager cm =
        PoolingHttpClientConnectionManagerBuilder.create()
            .setMaxConnPerRoute(10000)
            .setMaxConnTotal(10000)
            .setDefaultConnectionConfig(
                ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.ofMilliseconds(3000))
                    .setSocketTimeout(Timeout.ofMilliseconds(5000))
                    .build())
            .build();

    CloseableHttpClient httpClient =
        HttpClients.custom()
            .setConnectionManager(cm)
            .setConnectionReuseStrategy((request, response, context) -> false) // ← 핵심: 재사용 안 함
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setConnectionRequestTimeout(Timeout.ofMilliseconds(2000))
                    .setResponseTimeout(Timeout.ofMilliseconds(5000))
                    .build())
            .build();

    return RestClient.builder()
        .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
        .build();
  }

  private static String arg(String[] a, int i, String def) {
    return i < a.length ? a[i] : def;
  }

  private static String rootCause(Throwable t) {
    Throwable c = t;
    while (c.getCause() != null && c.getCause() != c) {
      c = c.getCause();
    }
    String msg = c.getMessage() == null ? "" : c.getMessage();
    if (msg.toLowerCase().contains("address already in use")) {
      return c.getClass().getSimpleName() + "(port-exhaustion)";
    }
    return c.getClass().getSimpleName();
  }
}
