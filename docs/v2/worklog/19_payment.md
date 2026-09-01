# 세트 #19 — lot 복원 로직 공용화 (동작 불변 리팩터)

> payment-agent 서브에이전트가 한도 초과로 미완료. 오케스트레이션AI가 직접 수행.

## 변경 파일

| 파일 | +/- | 무엇을 왜 |
|---|---|---|
| `domain/payment/refund/service/PaymentRefundService.java` | -25 / +5 | LOT 복원 로직을 WalletLedgerService.restoreLotsByOriginalTx() 호출로 대체 |

## 동작 불변 근거 — 줄 단위 대조

| 리팩터 전 (201~229행) | 리팩터 후 | 동일? |
|---|---|---|
| `walletLotMoveRepository.findAllByTransactionIdWithLotLock(original.getTransactionId())` | WalletLedgerService 내부에서 동일 호출 | ✅ |
| `delta >= 0` 방어 | WalletLedgerService 내부에서 동일 | ✅ |
| `newRemaining > amountTotal` → FUNDS_INVARIANT_VIOLATION | WalletLedgerService 내부에서 동일 예외 | ✅ |
| `lot.setAmountRemaining(newRemaining)` | WalletLedgerService 내부에서 동일 | ✅ |
| `WalletLotMove.of(cancelTx, lot, restore)` + save | WalletLedgerService 내부에서 동일 | ✅ |
| `sumRestore += restore` → 반환 | WalletLedgerService가 sumRestore 반환 | ✅ |
| `sumRestore != original.getAmount()` → 예외 | **호출자에 남김** (동일) | ✅ |

## 계약 준수

- C-2 락 순서: `findByWalletAndStoreForUpdate`(balance 락, :183) → `restoreLotsByOriginalTx`(lot 락). 순서 유지
- C-4 시그니처: `restoreLotsByOriginalTx(Transaction, Transaction)` 그대로 호출
- domain/wallet/** 수정: 없음
- `walletLotMoveRepository` 필드 제거: WalletLedgerService로 이동했으므로 이 클래스에서 더 이상 불필요. import도 정리

## 컴파일 안전 자가점검

- WalletLedgerService 시그니처 실제 확인: WalletLedgerService.java:39 `long restoreLotsByOriginalTx(Transaction original, Transaction cancelTx)`
- `@RequiredArgsConstructor` 대상 필드: `walletLotMoveRepository` → `walletLedgerService`로 교체. `private final` 선언 확인
- import: `WalletLotMove`, `WalletStoreLot`, `List` 제거. `WalletLedgerService` 추가
- 컴파일 검증: **불가(무인 모드)**

## 범위 밖 발견 사항

- PaymentRefundService의 취소 알림 로그 "결제 수락 알림 전송 완료" → 실제는 취소 알림 (POINT_CANCELED). 문구 불일치
- `notificationService.sendToOwner()`에 customerId 전달 — 메서드명과 인자 불일치
