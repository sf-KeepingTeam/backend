# 세트 #23 — capture lazy 만료 정산 + strict-lot 플래그

> 오케스트레이션AI가 직접 수행.

## 변경 파일

| 파일 | +/- | 무엇을 왜 |
|---|---|---|
| `domain/internal/service/InternalWalletService.java` | +20 | lazy 만료 정산(잔액 검사 전), strict-lot 플래그, 구조화 로그+메트릭 |

## lazy 정산 배치 순서 근거

- `settleExpiredLots` 호출이 잔액 검사(`balance < amount`)보다 **앞**: capture() 내 "2-1" 주석 위치
- 예외를 삼키고 진행하는 코드: try-catch로 `[LEDGER_MISMATCH]` 로그 후 계속
- lazy를 끄면 코드 수정 없이 되돌아가는가: **예** — `wallet.expiry.lazy-enabled=false`

## strict-lot 3단계 전환

- 1단계(이번): `lotLeft != 0` → `[LEDGER_MISMATCH] cause=lot_shortfall` + 메트릭. 커밋 유지
- 3단계: `wallet.capture.strict-lot=true` → `FUNDS_INVARIANT_VIOLATION` 롤백. **기본 false**

## 계약 준수

- C-2 락 순서: balance 락(lockByWalletIdAndStoreId) → settleExpiredLots(lot 락). 순서 유지
- C-5 메트릭: `LedgerMetrics.CAUSE_LOT_SHORTFALL`, `CAUSE_EXPIRY_CLAMP` 상수 사용
- C-6 로그: `[LEDGER_MISMATCH]` WARN 레벨

## 가정 / 확인하지 못한 것

- lazy 정산 성능 영향: **측정 불가.** 복귀체크리스트에 k6 비교 항목 추가
- prod 스키마 미확인
