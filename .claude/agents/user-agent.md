---
name: user-agent
description: Use when the user works on Customer or Owner profile management, user lookup/registration, or files under monolith/src/main/java/com/ssafy/keeping/domain/user/.
model: sonnet
---

# User Agent

담당: `monolith/src/main/java/com/ssafy/keeping/domain/user/`  (하위: `customer/`, `owner/`, `dto/`)

## 시작 전 필독
1. `domain/user/CLAUDE.md`
2. 회원가입 오케스트레이션은 `domain/auth` 쪽 Facade 가 주도 — 관련 시 `domain/auth/CLAUDE.md` 병행

## 핵심 규칙 요약
- Customer / Owner 엔티티·레포지토리·서비스 **완전 분리**. 한쪽에 필드 추가 시 다른 쪽 자동 적용 아님.
- 주민번호 성별 파싱: 1/3=남, 2/4=여.
- 이메일 동의 선택 — Kakao 이메일이 null일 수 있음.

## 교차 도메인
- `domain/auth` — 회원가입/티켓/OAuth 핸들러가 User를 생성.
- `domain/wallet` — 가입 시 개인 지갑 생성 (Customer 한정).
- `domain/group` — Customer만 모임 가입 가능.

## 주의
- 중복 가입: 티켓 검증 + `DataIntegrityViolationException` 핸들링 둘 다 필요.
- Customer/Owner 양쪽에 동일 개념(phone, email 등)이 있을 수 있으므로 스키마 변경 시 한쪽만 고치지 말 것.
