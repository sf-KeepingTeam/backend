# qr-service / domain/intent

CPQR(포인트) 결제의 핵심. **PaymentIntent** 생성 → PIN 승인 → 자금 캡처 → 상태 전이 2-phase 복구.

## 하위 구조

```
intent/
├── canonical/   CanonicalInitiate, CanonicalApprove (본문 정규화)
├── constant/    PaymentStatus (PENDING / APPROVED / DECLINED / CANCELED / EXPIRED / UNCERTAIN / ROLLED_BACK)
├── controller/  PaymentIntentController, PaymentApprovalController
├── dto/
├── model/       PaymentIntent, PaymentIntentItem (엔티티, @Version 낙관락)
├── repository/
└── service/     PaymentIntentService, FundsService, PaymentRecoveryService
```

## 핵심 규칙

### 2-phase 승인 플로우

```
  Customer PIN + ApproveReq
        │
        ▼
PaymentIntentService.approve
  ├─ Idempotency check (DONE → replay 200)
  ├─ Load Intent (PENDING 만 가능 — 외는 409/410)
  ├─ PinVerify (CustomerClient → monolith /internal/customers/{id}/pin-verify)
  ├─ FundsService.capture  ← write RestTemplate (2s/3s, retry 1, fail-fast)
  │    └─ WalletClient.capture(walletId, storeId, amount, capture-key)
  ├─ 성공: Intent = APPROVED
  ├─ 타임아웃/서킷 오픈: Intent = UNCERTAIN  (← 사용자 응답은 202 또는 구성된 응답)
  └─ 알림 발송 (고객 + 점주). 실패는 경고 로그만
```

### 결정적 캡처 키

`UUID.nameUUIDFromBytes("capture:" + intentPublicId)` — monolith WalletClient.capture 멱등성 보장.

### 복구 스케줄러 (`PaymentRecoveryService`)

- `@Scheduled(fixedDelay=10s)` — UNCERTAIN Intent 스캔.
- **Phase 1 (외부 API, 트랜잭션 없음, 10초 타임아웃)**: monolith `/internal/payments/check?idempotencyKey=` 로 캡처 실제 성공 여부 조회.
- **Phase 2 (짧은 저장 트랜잭션)**: Phase 1 결과로 Intent 상태만 업데이트. DB 커넥션 점유 중 외부 호출 금지.
- 캡처가 실제로 성공했으면 APPROVED, 실패했으면 DECLINED 또는 ROLLED_BACK.

### 상태 머신

`PENDING → APPROVED / DECLINED / CANCELED / EXPIRED / UNCERTAIN → ROLLED_BACK`.  잘못된 전이는 `PAYMENT_INTENT_STATUS_CONFLICT` 409.

### TTL

- Intent: 3분 (PENDING 외 approve 불가 → 3분 초과 시 `PAYMENT_INTENT_EXPIRED` 410).

### 본문 정규화

- initiate: `storeId` + `items[]`(정렬 후) → SHA-256 해시. 동일 키 다른 본문 = 409.
- approve: PIN은 **공백 제거 후 `\d{6}` 검증** 필수. 그 외 거부.

## 핵심 클래스

| 파일 | 역할 |
|---|---|
| `PaymentIntentService.initiate` | QR 세션 → 결제 의도 생성. 메뉴 동일 매장 검증, 고객 알림 |
| `PaymentIntentService.approve` | PIN 검증 → 자금 캡처 → 상태 전이 → 양쪽 알림 |
| `FundsService.capture` | `WalletClient.capture` 래퍼. 타임아웃/서킷 오픈 → UNCERTAIN |
| `PaymentRecoveryService` | UNCERTAIN 자동 복구 (10초 주기, 2-phase) |
| `PaymentIntent` 엔티티 | 상태 전이 가드 + `@Version` 낙관락 |

## 주의사항

1. **RestTemplate 오용**: `FundsService.capture`는 **write 템플릿**(2s/3s, retry 1, fail-fast). read 템플릿 쓰면 재시도 3회로 중복 캡처 위험.
2. **복구 중 DB 락 금지**: Phase 1에서 저장소/엔티티 접근 절대 금지.
3. **낙관락 충돌**: 동시 approve 들어오면 뒤쪽이 409 — 멱등성 키로 replay하게 유도.
4. **응답 코드**: 최초 `201`, replay `200`, in-progress `202 + Retry-After`. 프론트가 201만 성공 처리하면 재시도 오동작.
5. **메뉴/매장 검증**: 모든 item이 같은 매장 소속이어야 하고, 품절/비활성 메뉴는 거부.
6. **알림 실패**: 경고 로그만 — 본 비즈니스 중단 금지.
7. **saga_log 테이블**: 현재 미사용(Outbox용 스켈레톤). 복구 로직은 전용 스케줄러를 사용.

## 교차 참조

- `domain/idempotency/CLAUDE.md` — 멱등성 키 처리 철학.
- `domain/qr/CLAUDE.md` — initiate 입력(세션 토큰) 발급자.
- `acl/CLAUDE.md` — `WalletClient`/`CustomerClient`/`StoreClient`/`MenuClient` 타임아웃·Circuit Breaker.
- monolith `domain/wallet/CLAUDE.md` — `capture`/`restore`/`refund` 엔드포인트 상대쪽.
