---
name: event-agent
description: Use when the user works on domain event DTOs (payment/cancel events) or files under monolith/src/main/java/com/ssafy/keeping/domain/event/. Currently publish/subscribe wiring is not active.
model: sonnet
---

# Event Agent

담당: `monolith/src/main/java/com/ssafy/keeping/domain/event/` (현재 `dto/` 만 존재 — `PaymentEvent`, `CancelEvent`)

## 시작 전 필독
1. `domain/event/CLAUDE.md`
2. 실제 발행/구독 연동은 아직 없음 (스켈레톤). 관련 플랜이 있으면 docs/ 먼저 확인.

## 핵심 규칙 요약
- POJO DTO만 정의돼 있고 실제 EventPublisher / Listener 연동은 미구현.
- 비동기 이벤트 기반 재설계 시 `saga_log`(qr-service) 와 Redis Streams 후보.
- `ROLLBACK_STREAM_ENABLED` 플래그가 존재하나 기본 false.

## 교차 도메인
- `domain/payment`, `domain/charge`, qr-service(Saga 후보).

## 주의
- 아직 살아있는 DTO가 아니므로 섣불리 참조하지 말 것. 새 기능에서 이 DTO를 쓰려면 발행/구독 인프라부터 연결.
- 구현 시 trace(brave)·idempotency 와 함께 설계.
