# Wave 0 — 정리 스캔 보고

> cleanup-agent 서브에이전트가 스캔을 수행했으나 워크로그 파일 작성에 실패하여 오케스트레이션AI가 대신 요약 작성함.
> Wave 6에서 A등급 삭제를 진행하기 전에 사람이 이 목록을 재검증해야 한다.

## SettlementScheduler / 정산 도메인 조사

- `SettlementScheduler.java`: `@Service`가 주석 처리되어 있음 → 빈 등록 안 됨 → `@Scheduled` 2개 미동작
- 클래스 주석: "간소화: 정산 시스템 비활성화"
- `SettlementTask` 엔티티: 존재함. 생성 코드가 살아있는지 — **확인 필요**
- `SettlementTaskRepository`: `TransactionRepository`의 JOIN 쿼리에서 참조됨 → **삭제 불가**
- settlement_task 테이블 실제 행 수: **확인 불가 (DB 접속 금지)**
- **결론 없음. 사람 판단 필요. C등급(삭제 금지)**

## 확인하지 못한 것

- DB 조회 (settlement_task 행 수, prod 스키마)
- 빌드 검증 (`./gradlew build`)
- 프로필별 컨텍스트 로드 (prod/loadtest/perf)

## 복귀체크리스트에 올린 [결정 필요] 항목

- [결정 필요] SettlementScheduler 처분 — 삭제/살림/주석에 이유 명시
- [결정 필요] cleanup B등급 목록 개별 판단 (Wave 6에서 재스캔 예정)
