package com.ssafy.keeping.qr.loadtest;

import com.ssafy.keeping.qr.acl.StoreClient;
import com.ssafy.keeping.qr.acl.WalletClient;
import com.ssafy.keeping.qr.acl.dto.FundsCaptureRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 커넥션 풀 부하테스트용 백도어 컨트롤러 (실 환경 측정). loadtest.backdoor.enabled=true 일 때만 활성화.
 *
 * <p>실제 ACL 클라이언트(풀링된 RestClient)를 통해 monolith(또는 WireMock 대역)를 호출한다. 즉 진짜 qr-service 컨텍스트(Tomcat
 * 스레드 + Redis 캐시 + 풀)에서 측정한다.
 *
 * <p>풀 ON/OFF는 rest-template-pool.enabled 플래그로 토글하여 같은 부하로 A/B 비교한다.
 *
 * <p>측정 포인트:
 *
 * <ul>
 *   <li>/store : 캐시되는 읽기 (첫 호출만 monolith, 이후 Redis HIT) → "캐시가 호출을 줄인다" 확인
 *   <li>/balance : 캐시 안 되는 읽기 (read 풀, 매번 monolith)
 *   <li>/capture : 캐시 불가 쓰기 (write 풀, 결제마다 무조건 monolith) ← 풀 효과의 핵심
 *   <li>/payment : 결제 1건의 monolith fan-out 모사 (store + balance + capture)
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/loadtest/pool")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "loadtest.backdoor.enabled", havingValue = "true")
public class ConnectionPoolBenchmarkController {

  private final WalletClient walletClient;
  private final StoreClient storeClient;

  @GetMapping("/health")
  public ResponseEntity<Map<String, String>> health() {
    return ResponseEntity.ok(
        Map.of("status", "ok", "message", "connection-pool benchmark backdoor enabled"));
  }

  /** 캐시되는 읽기 (read 풀) — 첫 호출 후 Redis 캐시 HIT */
  @GetMapping("/store")
  public ResponseEntity<Map<String, Object>> store() {
    storeClient.getStore(1L);
    return ResponseEntity.ok(Map.of("ok", true));
  }

  /** 캐시 안 되는 읽기 (read 풀, 매번 monolith) */
  @GetMapping("/balance")
  public ResponseEntity<Map<String, Object>> balance() {
    walletClient.getBalance(1L, 1L);
    return ResponseEntity.ok(Map.of("ok", true));
  }

  /** 캐시 불가 쓰기 (write 풀, 결제마다 monolith) — 풀 효과의 핵심 경로 */
  @GetMapping("/capture")
  public ResponseEntity<Map<String, Object>> capture() {
    walletClient.capture(sampleCapture(), UUID.randomUUID().toString());
    return ResponseEntity.ok(Map.of("ok", true));
  }

  /** 결제 1건의 monolith fan-out 모사: 캐시 읽기 + 비캐시 쓰기 */
  @GetMapping("/payment")
  public ResponseEntity<Map<String, Object>> payment() {
    storeClient.getStore(1L); // 캐시 HIT (monolith 호출 거의 없음)
    walletClient.capture(sampleCapture(), UUID.randomUUID().toString()); // 캐시 불가 → 무조건 monolith
    return ResponseEntity.ok(Map.of("ok", true));
  }

  private FundsCaptureRequest sampleCapture() {
    return FundsCaptureRequest.builder()
        .walletId(1L)
        .storeId(1L)
        .customerId(1L)
        .amount(1000L)
        .items(
            List.of(
                FundsCaptureRequest.ItemSnapshot.builder()
                    .menuId(1L)
                    .menuName("loadtest-menu")
                    .unitPrice(1000L)
                    .quantity(1)
                    .build()))
        .build();
  }
}
