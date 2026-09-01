# 세트 #24 — 대사(Reconciliation) 서비스 + 스케줄러

> wallet-agent 서브에이전트가 코드를 생성했으나 사용량 한도로 워크로그 작성에 실패하여 오케스트레이션AI가 대신 작성함.

## 변경 파일

| 파일 | +/- | 무엇을 왜 |
|---|---|---|
| `domain/wallet/service/WalletReconciliationService.java` | [신규] | 전 지갑 대조 1회 실행. `runOnce()` |
| `domain/wallet/dto/ReconcileReport.java` | [신규] | #18에서 함께 생성됨 |
| `domain/wallet/dto/MismatchRow.java` | [신규] | #18에서 함께 생성됨 |

## 설계 판단

- 대사에서 **락을 잡지 않는다** — 결제를 막으면 안 됨
- 위양성 완화: 불일치 조합 짧은 지연 후 1회 재조회 (한계: 재조회 사이에도 결제 가능)
- samples 상위 100건만. 전체를 로그에 쏟지 않음
- `runOnce()`는 스케줄 무관하게 단독 호출 가능 (부하테스트 판정 도구)
- 자동 보정 코드 없음 — 탐지 + 기록까지만

## 가정 / 확인하지 못한 것

- 대사 쿼리 성능 — EXPLAIN 못 돌림. 페이지 단위 처리로 완화
- 위양성 비율 — 실측 필요
- 컴파일 검증: **불가(무인 모드)**
