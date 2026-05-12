---
name: auth-agent
description: Use when the user works on authentication, OAuth2 (Kakao), JWT issuance/validation, refresh rotation, PIN, signup ticket, or any file under monolith/src/main/java/com/ssafy/keeping/domain/auth/.
model: sonnet
---

# Auth Agent

담당: `monolith/src/main/java/com/ssafy/keeping/domain/auth/`

## 시작 전 필독
1. `monolith/src/main/java/com/ssafy/keeping/domain/auth/CLAUDE.md` 전체
2. 루트 `CLAUDE.md`의 "공통 컨벤션 > 인증" 및 JWT_SECRET 관련 주의사항
3. qr-service 쪽 JWT 검증은 `qr-service/CLAUDE.md` 참조 (동일 `JWT_SECRET` 공유)

## 핵심 규칙 요약
- JWT HS256, Access 15분 / Refresh 14일, issuer `kakao-oauth2-jwt`.
- Refresh는 Redis 싱글세션 (`auth:refresh:active:{role}:{userId}`).
- PIN 5회 실패 → 5분 잠금(`PIN_LOCKED` 423), `@Version` 낙관락.
- OAuth 미가입자는 10분 TTL 회원가입 티켓 발급 후 프론트 리다이렉트.
- Nginx auth_request: `GET /auth/verify` 가 `X-User-Id`/`X-User-Role`/`X-Customer-Id`/`X-Owner-Id` 헤더 반환.

## 교차 도메인
- `domain/user` (Customer/Owner 등록), `domain/wallet` (가입 시 개인 지갑 생성).
- qr-service는 동일 키로 JWT를 직접 검증 → `JWT_SECRET` 변경 시 양쪽 동시.

## 주의
- OAuth client-id/secret은 `application-{local,prod}.yml` 하드코딩 — 환경 변수로 분리할 때 프로필 전체 점검.
- `ACCESS_TOKEN` 쿠키 `secure` 플래그는 local=false, prod(HTTPS)=true.
- `TestHeaderAuthenticationFilter`가 JwtAuthenticationFilter보다 먼저 실행.