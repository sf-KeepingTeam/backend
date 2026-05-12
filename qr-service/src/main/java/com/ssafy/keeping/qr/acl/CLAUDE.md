# qr-service / acl (Anti-Corruption Layer)

monolith 와의 경계. 모든 내부 호출(`X-Internal-Auth`)과 Store/Menu 로컬 캐시, monolith → qr-service webhook 수신을 담당.

## 하위 구조

```
acl/
├── CustomerClient.java      PIN 검증 위임
├── MenuClient.java          CACHE_MODE에 따라 조회 분기
├── NotificationClient.java  monolith /internal/notifications/send
├── StoreClient.java         CACHE_MODE에 따라 조회 분기
├── WalletClient.java        capture / restore / check / refundForRecovery
├── cache/
│   ├── MenuCacheRepository.java
│   └── StoreCacheRepository.java
├── dto/
├── warming/
│   └── CacheWarmingService.java    시작 시 전량 프리로드 (WRITE_THROUGH 전용)
└── webhook/
    └── CacheWebhookController.java monolith → qr-service 캐시 무효화 수신
```

## 캐시 모드 (`CACHE_MODE` 환경변수)

| 모드 | 동작 | 쓰임새 |
|---|---|---|
| `WRITE_THROUGH` (기본) | 시작 워밍 + monolith webhook으로 갱신 | 운영 기본값 |
| `CACHE_ASIDE` | cache-aside (miss 시 monolith 조회 후 적재, Lazy Loading) | cold start 허용 시 |
| `NONE` | 캐시 우회, 매번 monolith 호출 | 디버깅/테스트 |

- `CACHE_WARMING_ENABLED=true` + `WRITE_THROUGH` 조합이 기본. 시작 시 전량 적재.
- webhook 수신 경로: `POST /internal/cache/stores/{storeId}`, `POST /internal/cache/menus/{menuId}` — `X-Internal-Auth` 검증.

## RestTemplate 용도별 분리 (config/RestTemplateConfig)

| 이름 | connect/read | retry | Circuit Breaker | 쓰임새 |
|---|---|---|---|---|
| **read** | 3s / 5s | 3 | strict(40%) | Store/Menu 조회 등 안전한 GET |
| **write** | 2s / 3s | 1 (fail-fast) | strict(40%) | `WalletClient.capture`, 상태 변경 호출 |
| **recovery** | 5s / 10s | 3 | recovery(60%) | `PaymentRecoveryService`의 check/refund |

## 아웃바운드 엔드포인트 (monolith)

모두 `X-Internal-Auth: {INTERNAL_AUTH_TOKEN}` 필수.

- `/internal/customers/{id}/pin-verify`
- `/internal/wallets/{walletId}/stores/{storeId}/balance`
- `/internal/wallets/{walletId}/stores/{storeId}/capture`
- `/internal/wallets/{walletId}/stores/{storeId}/restore`
- `/internal/wallets/{walletId}/refund`
- `/internal/payments/check?idempotencyKey=`
- `/internal/stores/{storeId}`, `/internal/stores/all`
- `/internal/menus/{menuId}`, `/internal/menus/batch`, `/internal/menus/all`
- `/internal/notifications/send`

## 핵심 클래스

| 파일 | 역할 |
|---|---|
| `WalletClient` | 용도별 RestTemplate 분리. `capture`/`restore`는 write, `checkPaymentForRecovery`/`refundForRecovery`는 recovery |
| `CustomerClient` | PIN 검증 단일 책임 |
| `StoreClient`/`MenuClient` | CACHE_MODE에 따라 캐시 → monolith fallback |
| `NotificationClient` | 고객/점주 알림 요청. 실패는 경고 로그만 |
| `CacheWarmingService` | `@EventListener(ApplicationReadyEvent)`에서 Store/Menu 전량 적재 |
| `CacheWebhookController` | monolith가 Store/Menu 변경 시 푸시 → 캐시 갱신 |

## 주의사항

1. **RestTemplate 혼동 금지**: 호출 용도와 템플릿 종류가 어긋나면 재시도 과다(중복 캡처) 또는 복구 실패.
2. **`X-Internal-Auth` 누락**: monolith 측에서 403. 네트워크 격리에만 의존 말 것.
3. **CACHE_MODE=WRITE_THROUGH 운영 시 webhook 실패 감시**: monolith 쪽 webhook 발송 실패 로그(`internal/webhook/QrServiceWebhookPublisher`)와 qr-service 측 수신 로그를 함께 확인.
4. **캐시 TTL/무효화**: webhook 수신 누락 시 stale 데이터 발생 가능 → 운영 중 의심되면 `CACHE_MODE=CACHE_ASIDE` 또는 재기동으로 워밍 재수행.
5. **Circuit Breaker 임계값**: strict 40% / lenient 70% / recovery 60% — 다른 임계값끼리 섞어 쓰면 복구 경로가 일찍 차단될 수 있음.
6. **NotificationClient 실패**: 결제 흐름 중단 금지 — 단순 로깅.

## 교차 참조

- `domain/intent/CLAUDE.md` — 가장 큰 소비자(`WalletClient.capture` 등).
- monolith `domain/internal/CLAUDE.md` — 아웃바운드 상대편 엔드포인트 구현.
- `config/RestTemplateConfig`, `config/CacheConfig`, `config/CacheModeConfig`.
