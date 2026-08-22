# PR #20: processRefund lot 복원 + originalTransactionId 필수화

## 변경 요약

`InternalWalletService.processRefund()`에 세 가지 변경 적용:

1. **`originalTransactionId` 필수 검증**: null이면 `REFUND_ORIGINAL_TX_REQUIRED` 예외. 유일한 호출자(qr `PaymentRecoveryService`)가 이미 항상 채워 보내므로 안전.
2. **원거래 customer 전파**: `refundTx` 생성 시 `.customer(originalTx.getCustomer())` 세팅. `transactions.customer_id`가 NOT NULL 제약이므로 기존 코드는 잠재적 flush 실패 버그였음 — 이번에 수정.
3. **lot 복원**: `walletLedgerService.restoreLotsByOriginalTx(originalTx, refundTx)` 호출 후 합계 검증 (`sumRestore != amount`이면 `FUNDS_INVARIANT_VIOLATION`).

## 롤백이 옳은 이유

- 매장 앞 실시간 경로가 아님 (qr 복구 스케줄러 경유)
- 스케줄러가 10초마다 재시도
- 실패 시 고객 손해 없음 (balance 복원 전 상태 유지)

## 수정 파일

- `monolith/.../domain/internal/service/InternalWalletService.java`
  - `WalletLedgerService` 의존성 추가
  - `processRefund()` 수정 (필수화 + customer 전파 + lot 복원 + 합계 검증)

## 테스트

- `InternalWalletServiceTest` (R-1 ~ R-5, R-7)
  - R-1: null originalTxId -> REFUND_ORIGINAL_TX_REQUIRED
  - R-2: wallet mismatch -> failed 응답
  - R-3: happy path (customer 전파 + lot 복원 + 성공)
  - R-4: restoreLotsByOriginalTx 호출 확인
  - R-5: sum mismatch -> FUNDS_INVARIANT_VIOLATION
  - R-7: lock timeout -> failed 응답, lot 복원 미실행
