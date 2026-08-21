# v1 — 동결된 이전 버전 기록

> **이 폴더는 동결(frozen)이다.** 내용을 수정하지 마라. 새 작업은 `docs/v2/`에서 한다.
> v1이 무엇을 했고 무엇을 결론냈는지 알고 싶으면 **이 문서 하나만 읽으면 된다.**

기간: ~2026-04 · 관련 PR: #11 ~ #15

---

## 1. v1이 만든 것

**Keeping** — 소상공인용 선결제 포인트 결제 플랫폼. 고객이 매장에 포인트를 미리 충전하고, QR을 찍어 그 포인트로 결제한다. 포인트는 **매장별로** 관리된다.

### 아키텍처 결정의 경위

원래 Spring Boot 모놀리식 한 대였다. **지갑 조회·메뉴 조회 트래픽이 몰리면 포인트 결제에서 타임아웃·지연이 발생**했고, "결제는 핵심 기능"이라는 판단으로 결제만 별도 EC2로 분리했다.

**분리의 목적은 성능이 아니라 부하 격리였다.** v1의 모든 설계 판단이 이 전제 위에 있다.

| 서비스 | 스펙 | 역할 |
|---|---|---|
| monolith | t3.medium | 인증, 회원, 매장/메뉴, 지갑/포인트, 충전, 알림, 통계, 정산 |
| qr-service | t3.small | QR 토큰, 결제 의도 생성/승인, 결제 복구 스케줄러 |

각자 **별도 MySQL + 별도 Redis**. 공유 DB도 공유 Redis 락도 없다. 통신은 REST + Webhook(`X-Internal-Auth`)뿐이다. 따라서 **두 서비스 간 공유 트랜잭션이 존재하지 않고, 정합성은 전적으로 멱등성·보상 설계에 의존한다.**

분리하면서 qr이 monolith 데이터를 알아야 하는 문제가 생겼고, **매장·메뉴는 Redis 캐시 사본 + Webhook 동기화**로 끊었다. **잔액은 끊지 않고** monolith에 차감 명령을 보내는 방식으로 남겼다.

---

## 2. v1이 해결한 것 — 7가지

| 작업 | 문제 | 해결 | PR |
|---|---|---|---|
| 위험 감사 | 결제·지갑·멱등·복구 잠재 결함 + 인프라 문서 오류 | 정밀 감사 리포트 + 설정 버그(`QR_SERVICE_URL`) 발견·수정 | #15 |
| Phase 0 검증 | 동시성 안전성 미검증 | k6 동시 부하 검증 + 로드테스트 인증 NPE 버그 발견·수정 | #15 |
| Toss 실연동 | PG가 stub뿐 | `PaymentGateway` 추상화 기반 stub↔real 토글 + 멱등키 | #11 |
| C4 환불 Saga | 외부 환불 후 DB 실패 → 돈 손실 | DB 선커밋(비관락) → 외부 환불, forward 멱등 재시도 | #12 |
| C3 충전 Saga | 적립 실패 시 보상이 롤백에 먹힘 → stuck | 오케스트레이터 분리 + `REQUIRES_NEW` 독립 보상(backward) | #12 |
| ShedLock | 복구 스케줄러 in-JVM 락 → 다중 인스턴스 이중복구 | Redis 분산락 + self-invocation 함정 회피 | #13 |
| Webhook 멱등·순서 | 비동기 재시도 재정렬 → 캐시 stale / 삭제 부활 | 단조 version 비교 + tombstone | #14 |

### 결과적으로 갖춰진 것 (v2에서 재사용, 다시 만들지 말 것)

- 멱등성 인프라 — 멱등키 + canonical body SHA-256 + 응답 스냅샷 재생 (양쪽 서비스)
- `UNCERTAIN` 상태 + `PaymentRecoveryService` (10초 주기, ShedLock). `UNCERTAIN`뿐 아니라 오래된 `PENDING`도 함께 스윕
- Resilience4j Circuit Breaker + Retry + fallback
- 캐시 3모드(`NONE`/`CACHE_ASIDE`/`WRITE_THROUGH`) + 시작 시 웜업 + Webhook 단조 version + tombstone
- 충전/환불 Saga 보상 (C3 backward / C4 forward)
- qr→monolith HTTP 용도별 커넥션풀 분리 (읽기 50 / 쓰기 30, HttpClient5)
- k6 부하 하니스 3세트 (`k6/prepayment`, `k6/performance-comparison`, `monitoring/load-tests`)

---

## 3. v1의 측정 결과

### 3-1. 커넥션 풀 실측 — 성공적인 측정

`v1/측정/측정결과_2EC2_커넥션풀.md`. 2-EC2 실환경에서 **관찰 → 통제 → 재관찰** 사이클을 3개 관측 도구(ss / 에러로그 / tcpdump)로 교차 검증했다.

| 지표 | 풀 OFF | 풀 ON |
|---|---|---|
| 처리량 RPS | 317 | 287 |
| TIME-WAIT peak | **1,960** | **0** |
| SYN(새 연결) / FIN | 48 / 85 | 11 / 0 |

**결론:** 커넥션 풀의 가치는 처리량이 아니라 **연결 안정성(포트 고갈 예방)**. 임시포트를 1,000개로 좁힌 가혹 조건에서 OFF는 `Cannot assign requested address` 다발, ON은 0건. Little's Law로 풀 크기 50의 근거까지 역산.

**이 문서의 미덕은 정직함이다** — "처리량은 개선되지 않았다", "포트 고갈은 이 부하에선 실제로 발생하지 않았다(예방적 개선)"라고 한계를 그대로 적었다. v2도 이 톤을 유지한다.

### 3-2. 성능 매트릭스 — 실패한 측정 (v2가 다시 하는 이유)

`k6/performance-comparison/results/matrix-result.md`. 2026-04-17, `CACHE_MODE`(PUSH/NONE) × 배경부하(유/무) 2×2.

| 조합 | CACHE_MODE | 배경부하 | Intent p95 | Approve p95 |
|---|---|---|---|---|
| 1 | PUSH | 없음 | 134ms | 530ms |
| 2 | NONE | 없음 | 164ms | 427ms |
| 3 | PUSH | 있음 | 119ms | 542ms |
| 4 | NONE | 있음 | 157ms | 537ms |

**가설 5개 중 4개 FAIL.** 문서가 실패 원인을 직접 적어놨다:

> "monolith가 충분히 바빠지지 않음 — CPU 20~40%, Wallet API p95 30ms"

**부하가 약해 병목이 발생하지 않은 측정이다.** 유일하게 PASS한 H2(격리 효과, 134→119ms)도 같은 이유로 신뢰할 수 없다 — 격리가 작동한 것인지 애초에 위협이 없었던 것인지 구분되지 않으며, -11%는 노이즈 범위다.

**그리고 README에 설계된 "상황1: 모놀리식 1대" 구성의 실측 로그가 없다.** 즉 "분리 전 vs 분리 후" 비교가 비어 있다.

---

## 4. v1이 남긴 미해결 항목

v2 검토(`docs/v2/구조검토_잔액소유권과_격리.md`)에서 확인된 것들.

1. **결제 임계 경로에 monolith 동기 호출 2개** — `verifyPin` + `capture`. 캐싱으로 끊은 건 매장·메뉴 2개뿐이고, 결제를 차단하는 호출 2개는 그대로 남았다.
2. **monolith 서버 측 자원 격리 없음** — qr 쪽 커넥션풀은 용도별로 나눴지만, 받는 monolith의 톰캣 스레드풀·Hikari 풀은 프론트 트래픽과 공유된다. 출구는 둘인데 입구가 하나다.
3. **LOT 만료 배치가 존재하지 않음 (확정 결함)** — `wallet_store_lot`에 만료일이 있고 조회 쿼리가 `expiredAt > now`로 거르지만, `wallet_store_balances.balance`를 줄이는 주체가 없다. → 만료 발생 시점부터 `balance > SUM(lot)`.
4. **`lotLeft != 0`을 로그만 찍고 커밋** — `InternalWalletService.capture()`. 3번과 결합하면 **없는 돈으로 결제가 성립**한다.
5. **대사(reconciliation) 배치 없음** — 이중 장부(`balance` / `lot`)의 불변식이 깨져도 탐지 수단이 없다.
6. **`CancelService`에 TODO 잔존** — `REFUND_PENDING` 건 주기적 재시도(forward recovery) 미구현.

---

## 5. v1이 내린 판단 (v2가 이어받는 전제)

- **Outbox를 쓰지 않은 이유** — 캐시 동기화는 `CACHE_WARMING` 전량 리로드 + `@Retryable`로 자가치유되는 self-healing 캐시라, Outbox는 이미 있는 보장을 다시 만드는 오버엔지니어링. at-most-once로 충분. **단, 결제 이벤트는 성격이 다르다(유실 시 복구 불가) — 이 구분은 v2에서도 유지한다.**
- **forward vs backward recovery** — 충전은 backward(미완성 충전은 결제 취소로 되돌림), 환불은 forward(고객이 받을 돈이라 abort 불가 → 재시도로 완료). 방향은 "그 작업을 안 해도 되나 / 반드시 끝나야 하나"로 결정.
- **타임아웃은 실패가 아니라 "모름"** — 실패로 처리하면 재결제로 이중 차감이 발생한다. `UNCERTAIN` 상태가 존재하는 이유.
- **비관락과 멱등성은 한 팀** — 비관락 = 줄 세우기(동시→순차), 멱등/상태체크 = 중복 거르기. 락이 먼저 잡혀야 그 안의 상태 확인이 신뢰 가능하다.

---

## 6. 색인

### `최종정리_학습과이력서.md` (v1 루트)
v1 전체의 대표 요약. 한 일 7가지 · 이력서 문장 · 면접 Q&A · 어필 포인트.

### `명세/`
| 파일 | 내용 |
|---|---|
| `DEVELOP_로드맵.md` | v1 당시의 개선 로드맵 (Phase 0~4 + FSM Spike). **v2 계획으로 대체됨** |
| `PHASE0_실행가이드.md` | k6 동시성 검증 |
| `PHASE1_ShedLock_구현명세.md` | 복구 스케줄러 분산락 |
| `PHASE2_Webhook멱등순서_구현명세.md` | 단조 version + tombstone |
| `PHASE3_Outbox_구현명세.md` | Outbox 설계 (**미구현** — 오버엔지니어링 판단) |
| `C3_충전보상SAGA_구현명세.md` | 충전 backward recovery |
| `C4_환불SAGA_구현명세.md` | 환불 forward recovery |

### `감사/`
`RISK_AUDIT_결제정합성.md` — 결제·지갑·멱등·복구 도메인 정밀 감사 원본. **v2 작업 전 반드시 읽을 것.**

### `측정/`
| 파일 | 내용 |
|---|---|
| `측정결과_2EC2_커넥션풀.md` | ★ 성공적인 측정의 본보기. 관찰→통제→재관찰 |
| `런북_2EC2_커넥션풀_테스트.md` | 재현 절차 |
| `학습_커넥션풀_적용정리.md` | 학습 정리 |

### `학습/`
`circuitbreaker-study-note.md`, `circuitbreaker-tuning-plan.md`, `STUDY_확장과팬아웃.md`, `면접_이력서항목_학습노트.md`, `학습_마스터인덱스_및_심화.md`, `학습_오늘한것3가지.md`, `0414Study/`(QR 분리·캐싱 / 결제 타임아웃 / 에러추적 / 모니터링)

### `포트폴리오/`
`portfolio/`(tech-highlights, loadtest-results, interview-qna, payment-safety-design, ppt-slides, technical-review, 0420면접대비), `PORTFOLIO_보강가이드.md`, `포폴_02-3_초안.md`

### `history/`
`msa-migration-journey.md`(MSA 이행 여정), `0318-troubleshooting.md`, `0319-refactoring.md`, `future-service-split-plan.md`

### `archive/`
원본 보존. `design/`(미구현 설계), `migration/`(MSA 이행 원본 + 3-서버 부하테스트 구성), `learning/`, `todolist/`, `portfolio/`

### `기타/`
`prompt_qr_improvements.md` — QR 서비스 개선 작업 프롬프트

---

## 7. 살아있는 문서는 여기 없다

현재 시스템의 상태·패턴·운영 문서는 v1이 아니라 상위 폴더에 있고 **계속 갱신된다**:

- `docs/architecture/` — 현재 시스템이 어떻게 생겼는가
- `docs/patterns/` — 공통 기술 패턴
- `docs/operations/` — 배포·부하테스트·운영
