# domain/wallet

개인 지갑 / 모임 지갑, 매장별 **잔액(Balance)** + **로트(Lot, FIFO)**. 결제 자금의 단위 원장 역할.

## 하위 구조

```
wallet/
├── constant/     WalletType (INDIVIDUAL/GROUP), LotStatus (ACTIVE/CANCELED), LotSourceType (CHARGE/TRANSFER_IN)
├── controller/   WalletController
├── dto/
├── model/        Wallet, WalletStoreBalance, WalletStoreLot, WalletLotMove
├── repository/
└── service/      WalletService
```

## 핵심 클래스

| 파일 | 역할 |
|---|---|
| `Wallet` | 지갑 루트. customer 1:1(INDIVIDUAL) 또는 group 1:1(GROUP). `walletType` enum 구분 |
| `WalletStoreBalance` | (wallet, store) 잔액. 유니크 제약. `addBalance`/`subtractBalance` |
| `WalletStoreLot` | 포인트 묶음 단위. `amountTotal`, `amountRemaining`, `expiredAt`(1년), `sourceType`, `contributorWallet`(모임 수신분의 출처 개인), `lotStatus` |
| `WalletLotMove` | Transaction ↔ Lot 변동 이력. `delta`(양/음). `delta=0` 금지(`of()` 팩토리에서 검증) |
| `WalletService.sharePoints` | 개인 → 모임 이체. Lot FIFO 소진. 멱등성 키 필수 |
| `WalletService.reclaimPoints` | 모임 → 개인 회수 |
| `WalletService.settleShareToIndividual` | 모임 해산 시 개인 기여분 반환(매장별 집계) |
| `WalletService.createOrGetIndividualWallet` | 중복 호출 안전 |

## 도메인 규칙

- **Lot FIFO**: `acquiredAt` 오름차순 소진. `lockAllByWalletIdAndStoreIdOrderByAcquiredAt` 사용.
- **Lot 누적**: 개인→모임 이체 시 동일 `originChargeTransaction`에 대해 **모임 지갑에는 TRANSFER_IN Lot을 하나만 유지**(누적). `findByWalletIdAndStoreIdAndOriginChargeTxIdAndSourceType().orElseGet(...)`.
- **개인 기여분 추적**: 모임 Lot의 `contributorWallet`에 기여한 개인 지갑 ID 저장. 회수/정산 시 이 필드로 판정.
- **조건부 차감**:
  - `WalletStoreBalanceRepository.decrementIfEnough` — `UPDATE ... WHERE balance >= amount`
  - `WalletStoreLotRepository.decrementLotIfEnough` — `UPDATE ... WHERE amount_remaining >= use AND lot_status='ACTIVE' AND expired_at > now`
- **비관락**: PESSIMISTIC_WRITE. `WalletStoreBalanceRepository.lockByWalletIdAndStoreId`는 3초 타임아웃 힌트 명시, **`WalletStoreLotRepository.lockAllByWalletIdAndStoreIdOrderByAcquiredAt`는 타임아웃 힌트가 없음** (DB 기본 동작). 잔액 락 타임아웃 시 `PAYMENT_IN_PROGRESS` 반환.
- **멱등성**: `sharePoints`/`reclaimPoints`는 `Idempotency-Key` 헤더 필수.
- **유효기간**: 만료 1년은 **CHARGE Lot 생성 시점**(`PrepaymentService.chargePoints`)에만 `now().plusYears(1)`로 설정. 공유/회수(TRANSFER_IN) Lot은 원본 Lot의 `expiredAt`을 그대로 상속. `isExpired()` 체크로 만료 Lot은 spendable 쿼리에서 제외.
- **불변식**:
  - Balance ≥ 0
  - `lot.amountRemaining ≤ lot.amountTotal`
  - move delta ≠ 0
- **해산 정산**: `settleShareToIndividual` — 매장별 그룹화 후 각 개인에게 기여 Lot 반환. 잔액 0 검증은 해산 로직(group 도메인)에서.

## 의존

- `domain.user.customer`, `domain.group`, `domain.store`
- `domain.payment.transactions` (`TransactionType.CHARGE/TRANSFER_IN/TRANSFER_OUT`)
- `domain.idempotency.IdempotencyService`
- `domain.notification.NotificationService` (공유 시 모임 알림)
- `global.exception` (`WALLET_*`, `FUNDS_*`, `OVER_*`)

## 엔드포인트

| 메서드 | 경로 | 인증 | 헤더 |
|---|---|---|---|
| GET | `/wallets/individual/balance` | 고객 | - |
| GET | `/wallets/individual/stores/{storeId}/detail` | 고객 | - |
| GET | `/wallets/groups/{groupId}` | 멤버 | - |
| GET | `/wallets/groups/{groupId}/balance` | 멤버 | - |
| GET | `/wallets/groups/{groupId}/stores/{storeId}/detail` | 멤버 | - |
| GET | `/wallets/both/balance` | 고객 | - |
| GET | `/wallets/{walletId}/stores/{storeId}/points/available` | 멤버 | 회수 가능 포인트 조회 |
| POST | `/wallets/groups/{groupId}/stores/{storeId}` | 멤버 | `Idempotency-Key` (공유) |
| POST | `/wallets/groups/{groupId}/stores/{storeId}/reclaim` | 멤버 | `Idempotency-Key` (회수) |

## 주의사항

1. 공유 Lot 중복 생성: 매번 새 TRANSFER_IN Lot을 만들면 추적 폭발. `orElseGet` 패턴 준수.
2. FIFO 정렬 누락: 락 쿼리에서 `acquiredAt` 정렬 빠지면 선입선출 깨짐.
3. 회수 조건: `contributorWallet == 해당 개인` 확인. 다른 멤버 기여분 회수 금지.
4. 만료 체크 누락: spendable 이외의 일반 조회로 만료 Lot 사용하지 않도록.
5. 타입 조합 강제: `individual.walletType==INDIVIDUAL && group.walletType==GROUP`. 그 외 조합 거부.
6. Lot 상태 전환 `ACTIVE → CANCELED`는 `lotStatus` 필드 사용. `sourceType`만으로는 불충분.
7. 멱등성 키 없는 공유 요청 거부. 클라이언트가 UUID 매번 새로 생성.
8. `WalletLotMove.of(delta=0)` 불가 — 변동 없는 기록 시도 시 실패.
