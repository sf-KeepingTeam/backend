# qr-service

CPQR(Customer-Presented QR) 결제를 담당하는 **별도 Spring Boot 서비스** (포트 8082). monolith와 같은 레벨의 독립 Gradle 프로젝트(`backend/qr-service/`). monolith의 `/api/qr`, `/cpqr/*/initiate`, `/payments/*/approve`, `/api/payments/intent/*` 경로를 Nginx가 이 서비스로 라우팅.

> **이름 주의**: `payment-service`가 아니라 `qr-service`. monolith 안에 별도로 `domain/payment`(토스 직접결제, 거래 원장)가 있으므로 이름이 겹치지 않도록 QR(포인트) 결제 전용임을 명시한다.

## 기술 스택

| 구분 | 값 |
|---|---|
| Java | 17 (build.gradle toolchain), Docker 런타임은 `eclipse-temurin:21-jre` |
| Spring Boot | 3.5.5 |
| Spring Cloud | 2024.0.0 |
| Resilience4j | 2.2.0 (Circuit Breaker + Retry — strict/lenient/recovery 3 종) |
| JWT | jjwt 0.12.3 (monolith와 같은 `JWT_SECRET` 공유, issuer `kakao-oauth2-jwt`) |
| DB | MySQL (별도 논리 DB `payment_service`), DATETIME(3) |
| Cache/Session | Redis (QR 토큰·세션용 `@RedisHash`) |
| Tracing/Metric | Micrometer Brave + Prometheus |

## 환경 변수

| 이름 | 기본값 | 설명 |
|---|---|---|
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | compose에서 세팅 | MySQL |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / 6379 (application.yml 기본값). `docker` 프로필에서는 `redis` / 6379로 오버라이드 | Redis |
| `JWT_SECRET` | - | **monolith와 동일 값 필수** |
| `MONOLITH_URL` | http://localhost:8080 | 내부 호출 대상 |
| `INTERNAL_AUTH_TOKEN` | `internal-service-token-12345` | `X-Internal-Auth` 헤더 값 |
| `CACHE_MODE` | `WRITE_THROUGH` | `NONE`/`CACHE_ASIDE`/`WRITE_THROUGH` |
| `CACHE_WARMING_ENABLED` | `true` | 시작 시 전량 프리로드 |
| `LOADTEST_BACKDOOR_ENABLED` | `false` | 부하테스트용 인증 우회 |

## 실행

```bash
# 로컬 실행 (qr-service 디렉토리 기준)
./gradlew bootRun

# Docker 이미지 (backend 루트에서)
(cd qr-service && ./gradlew clean bootJar -x test)
docker build -t qr-service ./qr-service
# 또는 docker-compose.msa.yml 의 build: context: ./qr-service 로 자동 빌드
```

헬스체크 `GET :8082/actuator/health`, 메트릭 `/actuator/prometheus`, Swagger `/swagger-ui.html`.

## 하위 구조

```
qr-service/src/main/java/com/ssafy/keeping/qr/
├── acl/          Anti-Corruption Layer (monolith 호출)
│   ├── cache/    Store/Menu 로컬 캐시
│   ├── dto/
│   ├── warming/  CacheWarmingService
│   └── webhook/  CacheWebhookController (monolith의 push 수신)
├── common/       exception, response
├── config/       SecurityConfig, JwtProperties, RedisConfig, CacheConfig,
│                 CacheModeConfig, RestTemplateConfig(read/write/recovery),
│                 SchedulerConfig, ObjectMapperConfig, AsyncConfig, JpaConfig
├── domain/
│   ├── idempotency/ IdempotencyKey, IdempotencyService (monolith 버전 독립 복제)
│   ├── intent/   PaymentIntent, PaymentIntentService, FundsService, PaymentRecoveryService
│   └── qr/       QrToken, QrScanSession, QrTokenService
├── loadtest/     IndexBenchmarkController
└── security/     JwtAuthenticationFilter, LoadTestAuthenticationFilter, UserPrincipal
```

## 핵심 클래스

| 파일 | 역할 |
|---|---|
| `QrTokenService` | QR 생성(TTL 10초), 스캔 → 소비 즉시 삭제 후 세션 발급(TTL 3분) |
| `PaymentIntentService.initiate` | QR 세션 기반 결제 의도 생성. 메뉴 동일 매장 검증, 고객 알림 |
| `PaymentIntentService.approve` | PIN 검증 → 자금 캡처 → 상태 전이 → 양쪽 알림 |
| `FundsService.capture` | `WalletClient.capture` 호출. 타임아웃/서킷 오픈 → Intent.UNCERTAIN |
| `PaymentRecoveryService` | `@Scheduled(10s)`. UNCERTAIN Intent 자동 복구 (2-phase: 외부 호출 트랜잭션 분리 → 짧은 저장 트랜잭션) |
| `WalletClient` | `capture`/`restore`/`checkPaymentForRecovery`/`refundForRecovery`. 용도별 RestTemplate 분리 |
| `CustomerClient` | PIN 검증 (monolith 위임) |
| `StoreClient`/`MenuClient` | 캐시 모드별(NONE/CACHE_ASIDE/WRITE_THROUGH) 조회 |
| `CacheWarmingService` | 시작 시 Store/Menu 전량 Redis 적재 (WRITE_THROUGH 전용) |
| `CacheWebhookController` | monolith → qr-service 캐시 갱신 수신 (`/internal/cache/stores/{id}`, `/internal/cache/menus/{id}`). `X-Internal-Auth` 검증 |
| `IdempotencyService` | monolith 동일 철학 — In-Progress/Done + body hash + 응답 스냅샷 |
| `PaymentIntent` (Entity) | 상태 머신 + 낙관락(`@Version`) |
| `JwtAuthenticationFilter` | monolith와 동일 `JWT_SECRET`·issuer로 검증 |

## 도메인 비즈니스 규칙

- **Payment Intent 상태**: `PENDING → APPROVED / DECLINED / CANCELED / EXPIRED / UNCERTAIN → ROLLED_BACK`. 잘못된 전이는 409.
- **Intent TTL**: 3분 (PENDING만 approve 가능).
- **QR TTL**: 10초(스캔 대기). 스캔 시 즉시 삭제(재사용 금지) → 세션 토큰 3분.
- **자금 캡처 멱등키**: `UUID.nameUUIDFromBytes("capture:" + intentPublicId)` — 결정적 생성.
- **캡처 실패 처리**: 타임아웃/서킷 오픈 → Intent = UNCERTAIN, 스케줄러가 별도 복구.
- **복구 2단계**: Phase 1(외부 API 호출, 트랜잭션 없음, 10초 타임아웃) / Phase 2(짧은 저장 트랜잭션). DB 커넥션 점유 중 API 대기 금지.
- **멱등성 응답 코드**: 최초 201, replay는 200 (`okReplay`). 진행 중은 202 + `Retry-After: 2`.
- **본문 정규화**:
  - initiate: `storeId` + 정렬된 `items[]` → SHA-256.
  - approve: PIN은 whitespace 제거 후 `\d{6}` 검증.
- **PIN 검증 위임**: monolith `/internal/customers/{id}/pin-verify` 호출.
- **메뉴/매장 검증**: 모든 아이템이 동일 매장 소속, 품절/비활성 거부.
- **알림**: 생성 / 승인 시 고객·점주에게. 실패 시 경고 로그만 — 본 비즈니스 계속.
- **캐시 모드**:
  - `WRITE_THROUGH`(기본): 시작 워밍 + monolith webhook으로 갱신.
  - `CACHE_ASIDE`: 조회 시 cache-aside (Lazy Loading).
  - `NONE`: 항상 monolith 호출.

## DB 마이그레이션

- `src/main/resources/db/migration/` 에 **`V2__add_saga_log.sql`만 존재** — Saga/Outbox용 `saga_log` 테이블이나 현재 미사용(향후 비동기 이벤트 처리 준비).
- **V1 마이그레이션 파일은 없다.** 주요 테이블(`payment_intent`, `payment_intent_item`, `idempotency_keys`)은 Hibernate `ddl-auto=update`로 생성되는 구조 — 완전한 Flyway 기반 스키마 관리는 아님.
- QR/세션은 Redis(`@RedisHash`).

## 모놀리식과의 경계

**qr-service 인바운드(Nginx 라우팅)**:

| 메서드 | 경로 | 인증 |
|---|---|---|
| POST | `/api/qr` | 고객 |
| GET | `/api/qr/{tokenId}` | 인증 (컨트롤러에 `@AuthenticationPrincipal` 명시 없음 — 실제 권한 검증은 서비스 계층에서 수행) |
| POST | `/api/qr/{tokenId}/scan` | 점주 |
| DELETE | `/api/qr/{tokenId}` | 고객 |
| POST | `/cpqr/{sessionToken}/initiate` | 점주 + `Idempotency-Key` |
| POST | `/payments/{intentPublicId}/approve` | 고객 + `Idempotency-Key` |
| GET | `/api/payments/intent/{intentPublicId}` | 인증 |
| POST | `/internal/cache/stores/{storeId}` | `X-Internal-Auth` |
| POST | `/internal/cache/menus/{menuId}` | `X-Internal-Auth` |

**qr-service 아웃바운드(monolith)**:
- `/internal/customers/{id}/pin-verify`
- `/internal/wallets/{walletId}/stores/{storeId}/balance`
- `/internal/wallets/{walletId}/stores/{storeId}/capture`
- `/internal/wallets/{walletId}/stores/{storeId}/restore`
- `/internal/wallets/{walletId}/refund`
- `/internal/payments/check?idempotencyKey=`
- `/internal/stores/{storeId}`, `/internal/stores/all`
- `/internal/menus/{menuId}`, `/internal/menus/batch`, `/internal/menus/all`
- `/internal/notifications/send`

모두 `X-Internal-Auth` 헤더 필수.

## 프로필

| 프로필 | 용도 |
|---|---|
| (default, `application.yml`) | 유일한 yml 파일. `docker`/`perf` 전용 yml은 없음 |
| `docker` (환경변수만) | 컨테이너 환경에서 `REDIS_HOST=redis` 등 환경변수로 오버라이드 |
| `perf` | 성능 테스트 전용 (별도 yml 없이 플래그로만 활성) |
| loadtest backdoor | `LOADTEST_BACKDOOR_ENABLED=true` 시 `X-Test-User-Id` + `X-Test-User-Role` 헤더 동시 요구 (둘 중 하나만 있으면 인증 실패) |

## 주의사항

1. **`JWT_SECRET` 불일치**: monolith와 달라지는 순간 전체 인증 실패.
2. **`X-Internal-Auth` 누락**: monolith 내부 API 호출 시 필수. 네트워크 구성만 믿지 말 것.
3. **Idempotency-Key 필수**: initiate/approve 둘 다. 누락 시 `IDEMPOTENCY_KEY_REQUIRED`.
4. **PIN 형식**: 공백 제거 후 `\d{6}` 검증. 그 외는 즉시 거부.
5. **Intent 만료**: PENDING 외 상태는 approve 불가 (`PAYMENT_INTENT_STATUS_CONFLICT` 409 또는 `_EXPIRED` 410).
6. **UNCERTAIN 복구**: 클라이언트는 장시간 기다리지 말 것. `PaymentRecoveryService`가 주기 처리.
7. **캐시 모드 선택**: `WRITE_THROUGH` 모드 + `CACHE_WARMING_ENABLED=true`가 기본. `CACHE_ASIDE`로 전환 시 cold start 지연 허용되는지 확인.
8. **RestTemplate 용도 혼동**: 읽기(3s/5s, 재시도 3), 쓰기(2s/3s, 재시도 1 — fail-fast), 복구(5s/10s, 재시도 3). `WalletClient.capture`는 반드시 **write** 템플릿.
9. **Circuit Breaker 임계값**: strict 40%, lenient 70%, recovery 60%. 용도마다 다른 것 혼동 주의.
10. **낙관락 `@Version`**: `PaymentIntent`에 필수. 동시 승인 시 충돌 → 409.
11. **응답 코드 재생**: 최초 201 vs replay 200 — 프론트가 201만 처리하면 재시도 시 오동작.
12. **saga_log 테이블**: 현재 미사용. 참고용으로 보고 직접 쓰지 말 것.
