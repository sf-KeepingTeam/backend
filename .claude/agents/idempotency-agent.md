---
name: idempotency-agent
description: Use when the user works on the shared Idempotency-Key mechanism (canonical body hash, response snapshot, replay), or files under monolith/src/main/java/com/ssafy/keeping/domain/idempotency/.
model: sonnet
---

# Idempotency Agent (monolith)

담당: `monolith/src/main/java/com/ssafy/keeping/domain/idempotency/`

## 시작 전 필독
1. `domain/idempotency/CLAUDE.md`
2. qr-service 동일 철학의 독립 복제본은 `qr-service/.../domain/idempotency/CLAUDE.md` — 양쪽 정책 동기화 책임.

## 핵심 규칙 요약
- 결제 계열(자금 캡처/환불/포인트 공유/선결제 승인) API는 `Idempotency-Key` 헤더 필수 (UUID).
- 본문은 `@Qualifier("canonicalObjectMapper")` 로 정규화 → SHA-256.
- 상태 머신: `IN_PROGRESS` → `DONE` / 실패. IN_PROGRESS 중 재요청은 202 + `Retry-After: 2`.
- 응답 스냅샷 JSON 저장 → 재요청 시 200 OK로 재생 (`okReplay`). 최초는 201.
- 스냅샷 직렬화 실패 시 `completeWithoutSnapshot` 폴백.

## 교차 도메인
- 모든 결제 계열 도메인 (`payment`, `charge`, `wallet`).
- qr-service `idempotency` (정책 동기화 필수).

## 주의
- 일반 ObjectMapper 사용 금지 — canonical 빈만.
- 다른 키 동일 본문은 허용, 동일 키 다른 본문은 `IDEMPOTENCY_BODY_CONFLICT` 409.
- 새 결제 API 추가 시 이 도메인을 반드시 경유하도록 설계.
