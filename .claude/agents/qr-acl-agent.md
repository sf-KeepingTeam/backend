---
name: qr-acl-agent
description: Use when the user works on qr-service ↔ monolith clients (Wallet/Customer/Store/Menu/Notification), the local Store/Menu cache, cache webhooks, or files under qr-service/src/main/java/com/ssafy/keeping/qr/acl/.
model: sonnet
---

# QR ACL Agent

담당: `qr-service/src/main/java/com/ssafy/keeping/qr/acl/`

## 시작 전 필독
1. `qr-service/.../acl/CLAUDE.md`
2. 상대편(monolith) 엔드포인트: `monolith/.../domain/internal/CLAUDE.md`
3. 설정: `qr-service/.../config/RestTemplateConfig`, `CacheConfig`, `CacheModeConfig`

## 핵심 규칙 요약
- 아웃바운드는 모두 `X-Internal-Auth` 필수.
- **RestTemplate 용도 분리**:
  - read (3s/5s, retry3, strict CB): 조회
  - write (2s/3s, retry1, fail-fast): `capture`/`restore` 등 상태 변경
  - recovery (5s/10s, retry3, recovery CB): check/refund for recovery
- **CACHE_MODE**: `PUSH`(기본, 시작 워밍 + webhook 갱신) / `PULL`(cache-aside) / `NONE`(항상 monolith).
- 수신 webhook: `POST /internal/cache/stores/{id}`, `/internal/cache/menus/{id}` — `X-Internal-Auth` 검증.

## 교차 도메인
- `qr-service/.../domain/intent` (`WalletClient.capture` 최대 소비자).
- monolith `domain/internal` (webhook 발행자 + 내부 API 제공자).

## 주의
- RestTemplate 혼동 금지 — write 경로에 read 쓰면 재시도로 중복 캡처.
- `PUSH` 모드 운영 시 webhook 실패 모니터링 필수 (stale 데이터).
- Circuit Breaker 임계값(strict 40% / lenient 70% / recovery 60%) 혼동 주의.
- `NotificationClient` 실패는 경고 로그만 — 본 비즈니스 중단 금지.
