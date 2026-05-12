# domain/charge

선결제(토스 카드 결제) 예약 → 승인 → 포인트 적립, 결제 취소(충전 롤백), 매장별 충전 보너스 정책, 정산 스케줄러.

## 하위 구조

```
charge/
├── canonical/   요청 본문 정규화 (멱등성 해시용)
├── controller/  PrepaymentController, CancelController, ChargeBonusController,
│                StoreChargeBonusController
├── dto/         요청·응답
├── model/       ChargeBonus, PaymentReservation, SettlementTask
├── repository/  (각 model 대응)
└── service/     PrepaymentService, CancelService, ChargeBonusService,
                 PaymentReservationScheduler, SettlementScheduler
```

## 핵심 클래스

| 파일 | 역할 |
|---|---|
| `PaymentReservation` | 서버 측 금액 확정 레코드. 프론트에서 변조 방지. 10분 유효 |
| `ChargeBonus` | (storeId, chargeAmount) 유니크. 매장별 금액대 → 보너스 % |
| `SettlementTask` | 정산 상태 기록용(현재 스케줄러 비활성화, 이력만 보존) |
| `PrepaymentService.reservePayment` | 예약 생성(금액·승인예정 고객·매장 기록) |
| `PrepaymentService.confirmPayment` | 토스 confirm + 금액 일치 검증 + 멱등성 + 보너스 적립 + `WalletStoreLot` 생성. 실패 시 토스 취소(보상 트랜잭션) |
| `CancelService.cancel` | USE 이전 충전(=미사용) 포인트 롤백. 비관락으로 동시 취소 방지 |
| `ChargeBonusService` | 점주 보너스 정책 CRUD + 소유권 검증 |
| `PaymentReservationScheduler` | 5분 주기 — 만료 예약 EXPIRED 마킹. 매일 03:00 — 30일+ 예약 삭제 |
| `SettlementScheduler` | (현재 `@Service` 주석처리 — 비활성화) 월 07:30 PENDING→LOCKED, 화 01:00 LOCKED→COMPLETED |

## 도메인 규칙

- **예약 TTL**: 10분. `confirmPayment`에서 `isExpired()` 체크.
- **금액 검증**: 예약 금액 ≠ 승인 요청 금액 → `INVALID_REQUEST`.
- **멱등성**: 동일 예약이 COMPLETED면 기존 거래 조회해서 `IdempotentResult.okReplay()` 반환.
- **보너스 계산 순서 고정**: `bonusAmount = paymentAmount * bonusPercentage / 100`. 역순 계산 금지(소수점 소실).
- **보너스 적용 조건**: `chargeAmount` **정확 일치**만. 금액대 범위가 아닌 포인트 매칭.
- **Lot 만료**: 적립 Lot의 `expiredAt = now + 1년`.
- **취소 가능 조건**: (1) 미사용 포인트만(`amountRemaining == amountTotal`), (2) 비관락 획득, (3) `refTransaction == null` 인 CHARGE 거래.
- **보상 트랜잭션**: 토스 결제 성공 후 DB 저장 실패 시 `compensatePayment`로 토스 취소 호출.
- **paymentKey 재사용**: `tossResponse.getPaymentKey()`가 `transactionUniqueNo`로 저장 — 네트워크 지연으로 응답 재수신 시 중복 위험.

## 의존

- `domain.payment.toss.TossPaymentClient`, `domain.payment.transactions` (거래 기록)
- `domain.wallet` (`WalletStoreLot`, `WalletStoreBalance`, `WalletRepository`)
- `domain.store`, `domain.user.customer`, `domain.user.owner`
- `domain.idempotency.IdempotentResult`
- `global.exception`

## 엔드포인트

| 메서드 | 경로 | 인증 | 비고 |
|---|---|---|---|
| POST | `/api/v1/stores/{storeId}/prepayment/reserve` | 고객 | 결제 예약(10분 유효) |
| POST | `/api/v1/stores/{storeId}/prepayment/confirm` | 고객 | `Idempotency-Key` 필수. 토스 승인 |
| GET | `/api/v1/customers/cancel-list` | 고객 | 취소 가능 거래 목록 |
| POST | `/api/v1/customers/payments/cancel` | 고객 | 충전 거래 취소 |
| GET/POST/PUT/DELETE | `/owners/stores/{storeId}/charge-bonus` | 점주 | 보너스 정책 CRUD (**`/api` 프리픽스 없음**) |
| GET | `/api/v1/stores/{storeId}/charge-bonus` | 고객 | 보너스 조회 |

## 주의사항

1. `confirmPayment` 내부 검증 순서: **현재 코드는 금액 검증 → 만료 확인 순서**. 만료된 예약에 대해서도 금액 검증을 먼저 수행하므로 불필요 로직이 일부 돌지만 기능상 문제 없음. 만료된 예약은 `EXPIRED` 마킹 후 예외 발생.
2. 비관락 누락: `walletStoreLotRepository.findByOriginChargeTransactionWithLock()` 꼭 사용. 일반 `findBy...` 쓰면 동시 취소 뚫림.
3. `paymentKey` 중복: 토스 응답 지연으로 같은 `paymentKey`가 두 번 저장될 수 있음. 유니크 제약 혹은 조회-후-저장 필요.
4. `SettlementScheduler` 주석 처리 상태. 의도하지 않게 활성화하면 정산 상태만 변경되고 포인트는 그대로 → 혼동 주의.
5. 취소 시 `refTransaction != null`이면 이미 취소된 거래.
6. 토스 confirm 응답 시각은 `Asia/Seoul`로 변환 필요(`TossPaymentGateway` 참고).
