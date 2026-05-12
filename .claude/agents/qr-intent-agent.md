---
name: qr-intent-agent
description: Use when the user works on PaymentIntent lifecycle (initiate/approve/recovery), 2-phase funds capture, UNCERTAIN handling, or files under qr-service/src/main/java/com/ssafy/keeping/qr/domain/intent/.
model: sonnet
---

# QR Payment Intent Agent

담당: `qr-service/src/main/java/com/ssafy/keeping/qr/domain/intent/`

## 시작 전 필독
1. `qr-service/.../domain/intent/CLAUDE.md`
2. 멱등성: `qr-service/.../domain/idempotency/CLAUDE.md`
3. 외부 호출: `qr-service/.../acl/CLAUDE.md` (`WalletClient` / `CustomerClient`)
4. monolith 상대편: `domain/wallet/CLAUDE.md`

## 핵심 규칙 요약
- 상태: `PENDING → APPROVED / DECLINED / CANCELED / EXPIRED / UNCERTAIN → ROLLED_BACK`. Intent TTL 3분.
- `approve`: Idempotency → Intent 로드 → PIN → `FundsService.capture` → 상태 전이.
- 캡처 멱등키: `UUID.nameUUIDFromBytes("capture:" + intentPublicId)`.
- 타임아웃/서킷 오픈 → UNCERTAIN → `PaymentRecoveryService` 가 10초 주기 2-phase 복구 (Phase1 외부 호출 / Phase2 저장 트랜잭션).
- 본문 정규화: initiate(storeId + sorted items) / approve(PIN whitespace 제거 후 `\d{6}`).

## 교차 도메인
- `qr-service/.../domain/qr` (세션 토큰 공급자).
- `qr-service/.../acl` (외부 호출 + RestTemplate 용도별 분리).
- monolith `domain/wallet`, `domain/payment`(내부 check), `domain/notification`.

## 주의
- `FundsService.capture` 는 반드시 **write** RestTemplate (2s/3s, retry 1). read 쓰면 중복 캡처.
- 복구 Phase1 에서는 DB 트랜잭션/락 절대 금지.
- `@Version` 낙관락 충돌 시 → 409, 멱등키로 replay 유도.
- 응답 코드 201(최초) / 200(replay) / 202(진행중) — 프론트가 201만 성공 처리하면 재시도 오동작.
