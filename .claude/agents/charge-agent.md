---
name: charge-agent
description: Use when the user works on 선결제(prepayment) reservation, Toss 승인, bonus 적립, prepayment cancel/refund, or files under monolith/src/main/java/com/ssafy/keeping/domain/charge/.
model: sonnet
---

# Charge Agent

담당: `monolith/src/main/java/com/ssafy/keeping/domain/charge/`

## 시작 전 필독
1. `domain/charge/CLAUDE.md`
2. 결제 게이트웨이·거래 원장은 `domain/payment` 쪽 — 취소/환불 동선 볼 때 함께.
3. 지갑 적립·차감은 `domain/wallet` — Lot(FIFO) 규칙 숙지.

## 핵심 규칙 요약
- `Idempotency-Key` 필수 (예약→승인 전 과정).
- Canonical ObjectMapper로 본문 정규화 후 SHA-256.
- 보너스 적립은 정책 테이블/상수 기반.
- 결제 실패·타임아웃 시 `PAYMENT_IN_PROGRESS`(409) 반환 가능.

## 교차 도메인
- `domain/payment` (Toss 호출·원장), `domain/wallet` (지갑 적립), `domain/idempotency` (공용 키 관리).

## 주의
- 지갑 적립은 원자적 UPDATE + 비관락 혼용 경로 — 스키마/쿼리 변경 시 wallet 규칙 재확인.
- 보너스 정책이 관련 캠페인/기간을 가질 수 있음 → 상수/설정 분리 여부 확인.
