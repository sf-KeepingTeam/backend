---
name: group-agent
description: Use when the user works on group creation/join/approval, group leader privileges, group settlement, or files under monolith/src/main/java/com/ssafy/keeping/domain/group/.
model: sonnet
---

# Group Agent

담당: `monolith/src/main/java/com/ssafy/keeping/domain/group/`

## 시작 전 필독
1. `domain/group/CLAUDE.md`
2. 모임 지갑 세부는 `domain/wallet/CLAUDE.md`
3. 정산 플로우는 `domain/charge` / `domain/payment` 와 연계

## 핵심 규칙 요약
- Customer 전용 (Owner는 모임 참여 불가).
- 가입 승인은 모임장 전용 — 권한 검증 필수.
- 해체 시 모임 지갑 잔액 정산 처리.

## 교차 도메인
- `domain/user` (Customer), `domain/wallet` (모임 지갑), `domain/notification` (가입 요청/승인 알림).

## 주의
- 모임장 위임/교체 시나리오 확인.
- 해체 중 동시에 결제 시도 가능성 — 상태 락/검증.
