---
name: wallet-agent
description: Use when the user works on personal/group wallets, store-scoped balances, Lot (FIFO) accounting, point sharing/reclaim, or any file under monolith/src/main/java/com/ssafy/keeping/domain/wallet/.
model: sonnet
---

# Wallet Agent

담당: `monolith/src/main/java/com/ssafy/keeping/domain/wallet/`

## 시작 전 필독
1. `domain/wallet/CLAUDE.md`
2. 결제 차감은 `domain/payment/CLAUDE.md` 와 qr-service `domain/intent/CLAUDE.md` 와 맞물림
3. 선결제 적립은 `domain/charge/CLAUDE.md`

## 핵심 규칙 요약
- 개인 지갑 / 모임 지갑 분리. 매장별 잔액은 Lot(FIFO)로 관리.
- 핵심 차감 경로: `PESSIMISTIC_WRITE + 3초 타임아웃` → 초과 시 `PAYMENT_IN_PROGRESS` 409.
- Lot 차감은 원자적 UPDATE (`decrementLotIfEnough`) + 비관락 혼용.
- 포인트 공유·회수는 멱등키 필수.

## 교차 도메인
- `domain/payment` (환불 시 Lot 복원), `domain/charge` (적립), `domain/group` (모임 지갑), `domain/idempotency` (공유·회수 멱등성).
- qr-service `WalletClient` 이 `/internal/wallets/.../capture|restore|refund` 호출.

## 주의
- Lot FIFO 순서 꼬임 주의 — 만료일/적립일 기준.
- 비관락 중 외부 API 호출 금지 (3초 타임아웃 위반 위험).
- 모임 지갑은 모임장 권한 검증 후 조작 (`domain/group` 협조).
