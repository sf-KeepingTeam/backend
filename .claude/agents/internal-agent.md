---
name: internal-agent
description: Use when the user works on /internal/* endpoints, qr-service ↔ monolith internal APIs, or cache invalidation webhooks under monolith/src/main/java/com/ssafy/keeping/domain/internal/.
model: sonnet
---

# Internal Agent

담당: `monolith/src/main/java/com/ssafy/keeping/domain/internal/`

## 시작 전 필독
1. `domain/internal/CLAUDE.md`
2. 상대편(소비자): `qr-service/.../acl/CLAUDE.md`
3. Nginx 라우팅: `gateway/nginx.conf` — `/internal/*` 외부 차단

## 핵심 규칙 요약
- 모든 `/internal/*` 엔드포인트는 `X-Internal-Auth: {INTERNAL_AUTH_TOKEN}` 검증.
- Nginx에서 외부 요청 403. 내부 네트워크만 허용.
- Store/Menu 변경 시 `QrServiceWebhookPublisher` 로 qr-service 캐시 갱신 요청.
- `QR_WEBHOOK_ENABLED=false` 로 비활성화 가능.

## 제공 엔드포인트 (대표)
- `/internal/customers/{id}/pin-verify`
- `/internal/wallets/{walletId}/stores/{storeId}/{balance|capture|restore}`, `/internal/wallets/{walletId}/refund`
- `/internal/payments/check?idempotencyKey=`
- `/internal/stores/...`, `/internal/menus/...`
- `/internal/notifications/send`

## 교차 도메인
- `domain/user`(PIN), `domain/wallet`(자금), `domain/payment`(check), `domain/store`/`domain/menu`(조회·캐시 invalidation), `domain/notification`(발송).

## 주의
- 토큰 기본값은 하드코딩(`internal-service-token-12345`). 배포에서 반드시 오버라이드.
- 내부 API 변경 시 qr-service `WalletClient`/`StoreClient` 등과 계약 테스트 (`qr-service/src/test/.../contract/StoreClientContractTest`) 업데이트.
- webhook 실패는 자동 재시도 전략 확인 — 실패 누락 시 캐시 stale.
