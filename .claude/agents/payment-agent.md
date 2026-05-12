---
name: payment-agent
description: Use when the user works on the Toss payment gateway integration, transaction ledger, refund, or any file under monolith/src/main/java/com/ssafy/keeping/domain/payment/.
model: sonnet
---

# Payment Agent

담당: `monolith/src/main/java/com/ssafy/keeping/domain/payment/`  (하위: `gateway/`, `toss/`, `transactions/`, `refund/`, `common/`)

## 시작 전 필독
1. `domain/payment/CLAUDE.md`
2. 멱등성 규칙은 `domain/idempotency/CLAUDE.md`
3. 지갑 차감/환원은 `domain/wallet/CLAUDE.md`
4. qr-service 쪽 포인트 결제 승인은 별도 — `qr-service/src/main/java/.../domain/intent/CLAUDE.md`

## 핵심 규칙 요약
- 결제 게이트웨이는 인터페이스로 추상화 (Toss 구현체). 다른 PG 추가 시 이 인터페이스 확장.
- 핵심 경로 `PESSIMISTIC_WRITE + 3초 타임아웃`. 초과 시 `PAYMENT_IN_PROGRESS` 409.
- 환불은 `refund/` 전용 서비스에서 처리. 외부 PG 상태와 내부 상태 동기화 필수.
- `PAYMENT_STATUS_CONFLICT` / `FUNDS_*` 에러 코드 사용.

## 교차 도메인
- `domain/charge` (선결제 경로에서 Toss 호출).
- `domain/wallet` (환불 시 지갑 복원).
- `domain/idempotency` (환불/승인 공용 키 관리).

## 주의
- 외부 API 결과는 `ExternalApiResponse`/`ExternalApiErrorResponse` 계열로 래핑.
- 거래 원장은 append-only. 기존 레코드 수정 대신 상태 전이 레코드 추가 방식 유지.
- wiremock(`wiremock/mappings/`)로 Toss 모킹 가능 — 로컬 테스트 시 활용.
