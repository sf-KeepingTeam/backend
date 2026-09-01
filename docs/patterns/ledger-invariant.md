# 원장 불변식 (Ledger Invariant)

## 원칙

**lot이 원장(정답)이고 balance는 파생값이다.** 둘이 다르면 lot을 믿는다.

```
SUM(wallet_store_lot.amount_remaining WHERE lot_status='ACTIVE') == wallet_store_balances.balance
    -- (wallet_id, store_id) 단위
```

회계에서 잔액과 원장이 다르면 원장을 믿는 것과 같다.

## 새 write 경로를 추가하는 사람이 지켜야 할 체크리스트

1. **balance와 lot을 같은 트랜잭션에서 짝으로 갱신한다.** 예외 없음.
2. **락 순서: balance 행 먼저, lot 행 나중.** 역순은 데드락이다.
   - `balanceRepository.lockByWalletIdAndStoreId(...)` → lot 조작
   - 기존 코드 전부가 이 순서다 (capture, refund, share, reclaim)
3. **lot 차감 후 남은 금액(`lotLeft`)이 0이 아니면 `[LEDGER_MISMATCH]` 로그 + 메트릭.**
   - `ledgerMetrics.mismatch(LedgerMetrics.CAUSE_LOT_SHORTFALL)`
   - `wallet.capture.strict-lot=true`이면 롤백
4. **lot 복원 시 상한 보호**: `newRemaining > amountTotal`이면 `FUNDS_INVARIANT_VIOLATION`
5. **만료 lot 처리**: `amount_remaining`을 0으로 만들고 `expired_settled_at`을 채운다. `LotStatus`는 ACTIVE 유지.
6. **메트릭 이름은 `LedgerMetrics` 상수를 사용.** 문자열 하드코딩 금지.
7. **Slack/이메일 알림을 만들지 않는다.** 관측은 로그 + Prometheus 메트릭이 전부다.

## 대사 (Reconciliation)

매일 03:40 KST에 전 지갑 대조. `WalletReconciliationService.runOnce()`로 스케줄 무관하게 단독 호출 가능.

불일치 방향별 의미:
- `balance > SUM(lot)`: 고객이 유령 잔액 보유. 함부로 깎지 말 것 — 원인 규명 먼저
- `balance < SUM(lot)`: 고객이 손해 보는 중. 올려주는 건 상대적으로 안전

자동 보정은 이번 범위에 없다.

## 관련 문서

- `docs/architecture/ADR-003-lot-expiry-and-dual-ledger.md`
- `docs/v2/설계안_작업1_LOT만료와_이중장부정합성.md`
