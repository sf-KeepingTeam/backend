---
name: qr-idempotency-agent
description: Use when the user works on qr-service's Idempotency-Key handling (independent copy of monolith policy), or files under qr-service/src/main/java/com/ssafy/keeping/qr/domain/idempotency/.
model: sonnet
---

# QR Idempotency Agent

담당: `qr-service/src/main/java/com/ssafy/keeping/qr/domain/idempotency/`

## 시작 전 필독
1. `qr-service/.../domain/idempotency/CLAUDE.md`
2. 원본 설계: monolith `domain/idempotency/CLAUDE.md` (정책 동일)

## 핵심 규칙 요약
- monolith 와 **별도 DB 스키마**(`payment_service`), 정책은 1:1 동기.
- 본문 정규화는 `canonicalObjectMapper` + SHA-256.
- 상태: `IN_PROGRESS`(202 + Retry-After 2) → `DONE`(200 replay) / 최초 성공 201.
- 적용 API: `/cpqr/{sessionToken}/initiate`, `/payments/{intentPublicId}/approve`, 내부 `capture` 결정적 키.

## 교차 도메인
- `qr-service/.../domain/intent` (가장 큰 소비자).
- monolith `domain/idempotency` (정책 동기화 대상).

## 주의
- 정책 변경 시 양쪽 서비스 동시 반영.
- 스냅샷 직렬화 실패 시 `completeWithoutSnapshot` 폴백 — 관찰 포인트.
- 일반 ObjectMapper 사용 금지.
