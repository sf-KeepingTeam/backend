# 세트 #18 — 원장 서비스 + 만료 배치

> wallet-agent 서브에이전트가 코드를 생성했으나 사용량 한도로 워크로그 작성에 실패하여 오케스트레이션AI가 대신 작성함.

## 변경 파일

| 파일 | +/- | 무엇을 왜 |
|---|---|---|
| `domain/wallet/model/WalletStoreLot.java` | +2 필드 +1 메서드 | `expiredSettledAt`, `expiredAmount` 매핑 + `settleExpired(now)` 멱등 메서드 |
| `domain/wallet/repository/WalletStoreLotRepository.java` | +3 쿼리 | `findExpiryCandidatePairs`, `lockUnsettledExpiredLots`, `sumActiveLotRemaining` |
| `domain/wallet/service/WalletLedgerService.java` | [신규] | `restoreLotsByOriginalTx`, `settleExpiredLots`, `sumActiveLotRemaining` |
| `domain/wallet/service/LotExpiryService.java` | [신규] | 만료 배치 본체. 2단 구조 (읽기→독립 트랜잭션) |
| `domain/wallet/service/LotExpiryScheduler.java` | [신규] | `@Scheduled` + `@SchedulerLock` |
| `domain/wallet/service/WalletReconciliationService.java` | [신규] | 전 지갑 대조 1회 실행 |
| `domain/wallet/dto/ExpirySweepReport.java` | [신규] | record |
| `domain/wallet/dto/ReconcileReport.java` | [신규] | record |
| `domain/wallet/dto/MismatchRow.java` | [신규] | record |

## 계약 준수

- C-2 락 순서 balance → lot: `LotExpiryService.java` Phase 2에서 `balanceRepository.lockByWalletIdAndStoreId` 먼저 → `settleExpiredLots` 안에서 `lockUnsettledExpiredLots`
- C-4 시그니처: 계약 그대로. 오케스트레이션AI가 `restoreLotsByOriginalTx` 상한 초과 시 클램프→예외로 수정함
- C-5 메트릭: `LedgerMetrics` 상수 사용, 하드코딩 0건
- LotStatus / TransactionType enum 변경: 없음
- 다른 도메인 파일 수정: 없음

## 컴파일 안전 자가점검

- `settleExpiredLots`의 `lockedBalance.setBalance(0L)` 호출: `WalletStoreBalance`에 `@Setter` 있음 확인
- LedgerMetrics 상수 `CAUSE_EXPIRY_CLAMP` 존재 확인
- import 누락 점검: 완료
- 컴파일 검증: **불가(무인 모드)**

## 가정 / 확인하지 못한 것

- MySQL 8.0+ 전제 (FOR UPDATE SKIP LOCKED)
- `findExpiryCandidatePairs`의 JPQL distinct + Pageable 조합이 실행 가능한지 — **확인 불가**
- 대사 쿼리 성능 — EXPLAIN 못 돌림
- lazy 정산 성능 영향 — **측정 불가**
