# domain/payment

결제 게이트웨이 추상화, 거래 원장(Transaction), 환불(멱등성 보장) 담당.

## 책임 범위

- **gateway 계층**: 토스/카카오/네이버 등 PG에 대한 추상 인터페이스. 현재 구현체는 토스뿐.
- **toss 계층**: 토스페이먼츠 REST 클라이언트 + 요청/응답 DTO.
- **transactions 계층**: 모든 자금 이동의 원장(Transaction) + 품목 스냅샷(TransactionItem). 통계 쿼리의 허브.
- **refund 계층**: 결제 취소(USE 거래의 CANCEL_USE 생성), 멱등성 필수.
- **common 계층**: `IdUtil` (UUID v4/v7).

## 하위 패키지 구조

```
payment/
├── common/      IdUtil
├── gateway/     PaymentGateway(인터페이스) + 공통 DTO + TossPaymentGateway
├── toss/        TossPaymentClient, TossPaymentConfig, Toss*Request/Response
├── transactions/ Transaction, TransactionItem, TransactionType, TransactionRepository
└── refund/      PaymentRefundService, PaymentRefundController, RefundResponse
```

## 핵심 클래스

### gateway
- `PaymentGateway` (interface): `processPayment(PaymentRequest)`, `cancelPayment(CancelRequest)`.
- `PaymentGatewayFactory`: `PaymentProvider` enum(TOSS/KAKAO/NAVER)으로 구현체 선택. 기본값은 `payment.provider` 설정.
- `TossPaymentGateway`: 공통 DTO ↔ 토스 DTO 변환. 응답 시각을 `Asia/Seoul`로 변환.

### toss
- `TossPaymentClient`: `confirmPayment`, `cancelPayment`. `Authorization: Basic Base64(secretKey + ":")` 포맷. timeout은 `TossPaymentConfig`(5초/10초).
- `TossPaymentConfirmResponse.isSuccess()`: `status == "DONE"`. 응답 필드는 `CardInfo` 중첩 객체를 포함하며, `CardInfo`의 필드는 `company, number, installmentPlanMonths, approveNo, useCardPoint, cardType, ownerType, acquireStatus, issuerCode, acquirerCode` (10개).
- `TossCancelResponse.isSuccess()`: `status in ("CANCELED", "PARTIAL_CANCELED")`.
- `TossCancelRequest`: 부분취소(`cancelAmount`), 가상계좌 환불계좌 정보 포함 가능.

### transactions
- `Transaction` (Entity): PK=`transactionId`, wallet/customer/store FK, **self-ref `refTransaction`** (취소 거래가 원 거래를 가리킴).
- `TransactionItem`: 품목 스냅샷 (menu 정보 복제 보관).
- `TransactionType` (enum):
  - `CHARGE` 포인트 충전, `USE` 포인트 사용
  - `CANCEL_CHARGE` 충전 취소, `CANCEL_USE` 사용 취소
  - `TRANSFER_IN` / `TRANSFER_OUT` 지갑 간 이체, `REFUND`
- `TransactionRepository`: ~80개 메서드. 페이징/통계(일/월/기간) + PESSIMISTIC_WRITE 락(`findByIdWithLock`, 5초). `SettlementTask`와 LEFT JOIN으로 정산 완료된 거래 제외하는 쿼리 다수.

### refund
- `PaymentRefundController`: `POST /api/stores/{storeId}/transactions/{txId}/refund`. 응답 DTO `RefundResponse` 필드: `transactionId`, `refundTransactionId`, `amount`, `refundedAt` (4개).
  - 헤더: `Idempotency-Key` 필수
  - 점주 인증 필요(`UserPrincipal`)
- `PaymentRefundService.fullCancel`:
  1. 멱등성 스코프: `(MERCHANT, ownerId, POST, /api/stores/.../refund, keyUuid, bodyHash)`.
  2. **DONE** → 저장된 응답 replay(200), **IN_PROGRESS** → 202 + Retry-After: 2초.
  3. 원거래 조회(락) → `TransactionType.USE`만 허용.
  4. 이미 `CANCEL_USE`가 있으면 **우호적 재생**(기존 취소건 그대로 반환).
  5. `WalletStoreBalance.addBalance(amount)` + 각 Lot `amountRemaining += (-moveDelta)` 복원.
  6. Invariant 검증: `sumRestore == original.amount` 아니면 `FUNDS_INVARIANT_VIOLATION`.
  7. `NotificationType.POINT_CANCELED` 고객 알림.

## 도메인 규칙

- **취소 가능 대상**: `USE` 거래만. 충전 취소(`CANCEL_CHARGE`)는 여기 refund 가 아니라 `domain/charge/CancelService`에서 처리.
- **Lot 복원 상한**: `lot.amountRemaining + restore ≤ lot.amountTotal`. 초과 시 invariant 위반.
- **동시성**: 원거래 `findByIdWithLock`(5초) + Wallet 쪽에서 별도 락. 락 타임아웃 시 `PAYMENT_IN_PROGRESS`.
- **멱등성 재생 응답코드**: 최초 처리는 201, replay는 200. qr-service도 동일 규칙.
- **refTransaction 추적성**: `CANCEL_USE.refTransaction = 원 USE 거래`. 중복 취소 방지는 `existsByRefTxIdAndType`.
- **타임존**: 토스 응답의 `OffsetDateTime`은 반드시 `atZoneSameInstant("Asia/Seoul")`.

## 다른 도메인/global 의존

- `domain.wallet`: `WalletStoreBalance`, `WalletStoreLot`, `WalletLotMove`, 각 Repository (잔액/Lot 복원).
- `domain.user.customer`, `domain.store`, `domain.menu`: Entity 참조.
- `domain.idempotency`: `IdempotencyService`(스코프 생성·replay).
- `domain.notification`: 환불 완료 알림.
- `global.exception`: `ErrorCode`, `CustomException`.
- `global.response`: `ApiResponse`.

## 엔드포인트

| 메서드 | 경로 | 인증 | 헤더 |
|---|---|---|---|
| POST | `/api/stores/{storeId}/transactions/{txId}/refund` | 점주 | `Idempotency-Key` |

(기타 결제 승인·조회 엔드포인트는 `charge/`, `internal/`, `qr-service`에 흩어져 있음.)

## 주의사항

1. **Basic Auth 콜론 누락**: `secretKey + ":"` 뒤 콜론 필수. 빠지면 토스 401.
2. **SettlementTask JOIN 쿼리 혼동**: 정산과 무관한 통계에 정산 필터 걸린 메서드 잘못 쓰기 쉬움 — 메서드명 `findValidTransactions*`는 "정산 가능한 것만" 의미.
3. **멱등키 없는 재요청**: 헤더 누락 시 `IDEMPOTENCY_KEY_REQUIRED`. 우회 금지.
4. **Lot 복원 후 검증 생략**: 합계 검증 빼먹으면 invariant 파손. 반드시 거치는 라인 유지.
5. **CANCEL_CHARGE는 이 도메인에 없음**: 충전 취소 로직은 `charge` 도메인.
6. **timezone 변환 누락**: `approvedAt`/`canceledAt`이 PG 응답 그대로면 UTC — 통계 쿼리가 Asia/Seoul 전제라 어긋남.
