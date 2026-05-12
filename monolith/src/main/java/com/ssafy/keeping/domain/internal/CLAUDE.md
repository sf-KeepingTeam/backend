# domain/internal

`/internal/*` — qr-service ↔ monolith 내부 통신 전용. **외부 접근은 Nginx에서 403, 애플리케이션 레벨에서 `X-Internal-Auth` 헤더로 한 번 더 검증**.

## 하위 구조

```
internal/
├── controller/   InternalCustomerController, InternalPaymentController,
│                 InternalWalletController, InternalStoreController,
│                 InternalMenuController, InternalNotificationController
├── dto/
├── exception/    InternalApiAuthException
├── service/      InternalPaymentService, InternalWalletService
└── webhook/      QrServiceWebhookPublisher (monolith → qr-service)
```

## 핵심 클래스

| 파일 | 역할 |
|---|---|
| `InternalCustomerController` | 고객 조회, PIN 설정/검증 |
| `InternalPaymentController` | 멱등키 기준 결제 존재 확인 (qr-service 복구용) |
| `InternalWalletController` | 잔액 조회, **자금 캡처**, **환불**, **복원** — 모두 `Idempotency-Key` 필수 |
| `InternalStoreController` / `InternalMenuController` | 단건/배치 조회 + 캐시 워밍용 전체 조회(`/all`) |
| `InternalNotificationController` | CUSTOMER/OWNER 타겟 알림 발송 |
| `InternalPaymentService` | 멱등키로 기존 결제 레코드 조회 |
| `InternalWalletService` | 캡처/환불/복원. `Idempotency-Key` 기반 원자적 처리 |
| `QrServiceWebhookPublisher` | Store/Menu 변경 시 qr-service의 `/internal/cache/...`로 비동기 Push. Spring Retry(500ms/1s/2s, 3회). 실패 시 `@Recover`에서 로깅만 — fire-and-forget |
| `InternalApiAuthException` | 토큰 불일치 시 401 |

## 도메인 규칙

- **토큰 검증 공통 패턴**: 모든 컨트롤러 진입부에서 `validateInternalAuth(header)` — `application.yml`의 `internal.auth-token`과 비교.
- **멱등성 보장 엔드포인트**: 자금 캡처, 환불, 포인트 복원. 바디 해시 + 스냅샷 기반 replay.
- **동시성**: 잔액/캡처 시 `WalletStoreBalanceRepository.lockByWalletIdAndStoreId` (PESSIMISTIC, 3초). 타임아웃 시 `PAYMENT_IN_PROGRESS`(409).
- **거래 기록**: 캡처 = `TransactionType.USE`, 환불 = `REFUND`. `TransactionItem` 다건 저장.
- **webhook 재시도 정책**: 지수 백오프(500ms → 1s → 2s), 3회, 실패 로깅만 — 캐시 불일치 가능 → 모니터링 필수.
- **Webhook 토글**: `qr-service.webhook.enabled=false`로 차단 가능.
- **캐시 워밍용 전체 조회**: `GET /internal/stores/all`, `/internal/menus/all` — 필터 없음, 활성 데이터 전부 반환. 대용량 주의.

## 의존

- 거의 모든 도메인을 사용: `customer`, `store`, `menu`, `wallet`, `payment.transactions`, `notification`, `auth.pin`, `idempotency`
- `global.exception`

## 엔드포인트

| 메서드 | 경로 | 인증 | 멱등 |
|---|---|---|---|
| GET | `/internal/customers/{customerId}` | X-Internal-Auth | 고객 조회 |
| POST | `/internal/customers/{customerId}/pin-set` | X-Internal-Auth | PIN 설정 |
| POST | `/internal/customers/{customerId}/pin-verify` | X-Internal-Auth | PIN 검증 |
| GET | `/internal/wallets/{walletId}/stores/{storeId}/balance` | X-Internal-Auth | 잔액 조회(락) |
| POST | `/internal/wallets/{walletId}/stores/{storeId}/capture` | X-Internal-Auth | ○ 자금 캡처 |
| POST | `/internal/wallets/{walletId}/stores/{storeId}/restore` | X-Internal-Auth | 캡처 복원 (**`/stores/{storeId}`** 포함) |
| POST | `/internal/wallets/{walletId}/refund` | X-Internal-Auth | ○ 환불 |
| GET | `/internal/payments/check?idempotencyKey=` | X-Internal-Auth | 결제 존재 여부 (qr-service 복구용) |
| GET | `/internal/stores/{storeId}`, `/internal/stores/all` | X-Internal-Auth | 매장 단건/전량 |
| GET/POST | `/internal/menus/{menuId}`, `/internal/menus/batch`, `/internal/menus/all` | X-Internal-Auth | 메뉴 |
| POST | `/internal/notifications/send` | X-Internal-Auth | 알림 발송 |

Outbound (monolith → qr-service, `QrServiceWebhookPublisher`):

| 메서드 | 경로 | 비고 |
|---|---|---|
| POST | `{qr-service.url}/internal/cache/stores/{storeId}` | Store CUD 반영 |
| POST | `{qr-service.url}/internal/cache/menus/{menuId}` | Menu CUD 반영 |

## 주의사항

1. 신규 `/internal/*` 엔드포인트 추가 시 `validateInternalAuth` 호출 필수. 빠뜨리면 외부 노출 위험.
2. `Idempotency-Key`가 UUID 문자열이 아니면 400. body hash 불일치도 400.
3. 웹훅 실패는 로깅만 하고 삼킴 — 캐시 불일치 장기화 가능. Prometheus 알람 권장.
4. Notification 요청 DTO의 필드 이름(`targetType`/`targetId`/`type`)과 내부 getter(`getReceiverType`/`getReceiverId`/`getNotificationType`)가 어긋남 — 스키마 변경 시 양쪽 동기화.
5. 락 타임아웃 3초는 부하 시 자주 발생 — 클라이언트(qr-service)는 `Retry-After` 준수.
6. `originalTransactionId` 검증은 선택적 — null 허용. 필요 시점엔 명시적으로 확인.
