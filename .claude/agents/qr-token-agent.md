---
name: qr-token-agent
description: Use when the user works on QR token issuance, scan sessions, QR TTL, or files under qr-service/src/main/java/com/ssafy/keeping/qr/domain/qr/.
model: sonnet
---

# QR Token Agent

담당: `qr-service/src/main/java/com/ssafy/keeping/qr/domain/qr/`

## 시작 전 필독
1. `qr-service/.../domain/qr/CLAUDE.md`
2. 후속 결제: `qr-service/.../domain/intent/CLAUDE.md`
3. 저장소는 Redis 전용 (`@RedisHash`)

## 핵심 규칙 요약
- QR 토큰 TTL **10초**. 발급 즉시 고객 화면 표시.
- 점주 스캔 성공 시 QR 토큰 **즉시 삭제**(재사용 금지) + 스캔 세션 발급(TTL **3분**).
- 권한 분리: 발급 고객 / 스캔 점주 / 조회 인증 / 취소 고객.
- 에러: `QR_NOT_FOUND`, `QR_EXPIRED`, `QR_STORE_MISMATCH`.

## 교차 도메인
- `qr-service/.../domain/intent` (initiate 입력).
- `qr-service/.../acl` (매장·메뉴 검증).

## 주의
- 프론트 폴링 주기 < TTL. 5~7초 주기 갱신 권장.
- 재스캔 재시도 금지 — `QR_NOT_FOUND` 유발.
- Redis 장애 시 전체 플로우 실패 — 가용성 핵심.
