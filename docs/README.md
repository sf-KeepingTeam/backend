# docs/

Keeping 백엔드 문서. 프로젝트 전반 개요는 루트의 [../CLAUDE.md](../CLAUDE.md) 와 [../README.md](../README.md) 를 먼저 참고.

## 구조 — 세 갈래

```
docs/
├── architecture/  현재 시스템이 어떻게 생겼는가   ← 살아있음, 계속 갱신
├── patterns/      공통 기술 패턴                  ← 살아있음
├── operations/    배포·부하테스트·운영            ← 살아있음
├── decisions.md   의사결정 인덱스                 ← 살아있음
├── failures.md    실패 기록 인덱스                ← 살아있음
│
├── v2/            현재 진행 중인 작업             ← 살아있음 ★
└── v1/            동결된 이전 버전 기록           ← 수정 금지 ❄
```

**핵심 규칙:**

- `v1/` 은 **동결**이다. 열어보되 고치지 않는다. v1이 뭘 했는지는 [`v1/README.md`](./v1/README.md) **하나만 읽으면 된다.**
- `v2/` 는 **현재**다. 작업이 진행되면 갱신한다. 시작점은 [`v2/README.md`](./v2/README.md).
- `architecture/` · `patterns/` · `operations/` 는 버전과 무관하게 **현재 시스템의 사실**을 담는다. v2 작업으로 시스템이 바뀌면 여기도 함께 갱신한다.

---

## v2 — 현재 진행 중

[**v2/README.md**](./v2/README.md) — 목표, 작업 4개, 측정 계획, 순서, 담당 분리

| 파일 | 내용 |
|---|---|
| [`v2/구조검토_잔액소유권과_격리.md`](./v2/구조검토_잔액소유권과_격리.md) | 잔액 소유권 이전을 하지 않기로 한 근거, 남은 결함 3개 |
| [`v2/프롬프트_인프라AI.md`](./v2/프롬프트_인프라AI.md) | 인프라·측정 담당 AI 초기 프롬프트 |
| [`v2/프롬프트_코드기획AI.md`](./v2/프롬프트_코드기획AI.md) | 코드 설계·구현 담당 AI 초기 프롬프트 |

**한 줄 요약:** v1의 확정 결함(LOT 만료 미처리)을 고치고, 부하 격리가 실제로 됐는지 측정으로 답하고, 남은 동기 결합 2개를 끊는다. 소유권 이전은 하지 않는다.

---

## v1 — 동결

[**v1/README.md**](./v1/README.md) — v1 전체 요약 · 한 일 7가지 · 측정 결과 · 미해결 항목 · 색인

```
v1/
├── README.md              ★ 이것만 읽어도 v1 파악 가능
├── 최종정리_학습과이력서.md  v1 대표 요약 (이력서·면접 Q&A 포함)
├── 명세/                  C3·C4 Saga, PHASE0~3, DEVELOP_로드맵
├── 감사/                  RISK_AUDIT_결제정합성
├── 측정/                  커넥션풀 실측·런북
├── 학습/                  CB·팬아웃·면접노트·0414Study
├── 포트폴리오/            portfolio/ 전체 + 보강가이드
├── history/               MSA 이행 여정·트러블슈팅
├── archive/               원본 보존 (design/migration/learning/todolist)
└── 기타/
```

---

## 살아있는 문서

### architecture/ — "이 기능이 현재 어떻게 되어 있지?"

- [overview.md](./architecture/overview.md) — 전체 아키텍처·토폴로지·스택
- [service-communication.md](./architecture/service-communication.md) — ACL, `/internal`, `X-Internal-Auth`, webhook
- [jwt-authentication.md](./architecture/jwt-authentication.md) — JWT 검증 방식
- [qr-payment-flow.md](./architecture/qr-payment-flow.md) · [qr-payment-sequence.md](./architecture/qr-payment-sequence.md) — QR 결제 흐름·시퀀스
- [nginx-gateway.md](./architecture/nginx-gateway.md) — Nginx 라우팅
- [ADR-001-push-based-caching.md](./architecture/ADR-001-push-based-caching.md) — PUSH 캐시 선택 배경
- [ADR-002-payment-stability-enhancement.md](./architecture/ADR-002-payment-stability-enhancement.md) — 결제 안정성 보강

### patterns/ — "이 기술 패턴을 어떻게 구현했지?"

- [concurrency-and-idempotency.md](./patterns/concurrency-and-idempotency.md) — 비관락·원자 UPDATE·멱등성 키
- [resilience.md](./patterns/resilience.md) — Resilience4j 3 프로필·UNCERTAIN 복구
- [caching.md](./patterns/caching.md) — NONE/PULL/PUSH·웜업·Webhook
- [observability.md](./patterns/observability.md) — Prometheus·Brave·Actuator
- [contract-testing.md](./patterns/contract-testing.md) — Spring Cloud Contract
- [acl-pattern.md](./patterns/acl-pattern.md) — Anti-Corruption Layer

### operations/ — "어떻게 배포하지?"

- [배포하기가이드.md](./operations/배포하기가이드.md)
- [docker-compose.md](./operations/docker-compose.md) — MSA 실행
- [aws-load-test.md](./operations/aws-load-test.md) — EC2 배포 + k6 부하테스트
- [observability-runbook.md](./operations/observability-runbook.md)

---

## 문서를 찾는 법

| 질문 | 위치 |
|---|---|
| 지금 뭘 하고 있지? | `v2/README.md` |
| 예전에 뭘 했지? | `v1/README.md` |
| 이 기능이 현재 어떻게 돼 있지? | `architecture/` |
| 이 패턴을 어떻게 구현했지? | `patterns/` |
| 어떻게 배포하지? | `operations/` |
| 왜 이렇게 결정했지? | `decisions.md` → `v1/README.md` §5 → 각 ADR |
| 면접/발표에서 뭘 보여주지? | `v1/최종정리_학습과이력서.md`, `v1/포트폴리오/` |
