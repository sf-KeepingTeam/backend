# #61 Redis 매핑 — QrFlowRedisStore + scan/initiate 연결 (2026-09-01)

## 신규 파일

### `domain/qr/repository/QrFlowRedisStore`

키 3종:

| 키 | 값 | 저장 시점 | TTL |
|---|---|---|---|
| `qrflow:s2t:{sessionToken}` | tokenId | scan 직후 | 180 s |
| `qrflow:active:{tokenId}` | "1" | scan 직후 | 180 s |
| `qrflow:intent:{tokenId}` | `{intentPublicId}:{customerId}` | AFTER_COMMIT 리스너 | 180 s |

`mgetIntentArrival(List<String> tokenIds)` — `MGET qrflow:intent:*` 일괄 조회.
null-safe: `multiGet` 반환이 null 이면 `Collections.nCopies(n, null)` 대체.

### `domain/intent/event/QrFlowIntentReadyEvent` (record)

`(tokenId, intentPublicId, customerId, storeId, amount, items)` — 엔티티 없음, 값만 복사.

### `domain/intent/service/QrFlowIntentReadyListener`

`@TransactionalEventListener(AFTER_COMMIT)` — @Async 없음.
이유: @Async + DiscardPolicy 조합이면 Redis 쓰기가 drop 되어 폴링 fallback 키가 생성되지 않음.

## 수정 파일

### `QrTokenService.scanAndConsumeQr()`

`scanSessionRepository.save(session)` 직후 추가:
```
qrFlowRedisStore.saveSessionToTokenMapping(sessionToken, tokenId)
qrFlowRedisStore.saveActiveToken(tokenId)
```
실패 시 warn 로그만 — 결제 플로우 중단 금지. GETDEL 원자성 유지.

### `PaymentIntentService.initiate()`

itemViews 구성 직후, 기존 알림 블록 바로 앞에 추가:
```
tokenIdForFlow = qrFlowRedisStore.getTokenIdForSession(sessionToken)
if present: publishEvent(QrFlowIntentReadyEvent(...))
```
이벤트는 @Transactional 내부에서 발행 → AFTER_COMMIT 리스너가 커밋 후 처리.
TX 경계 불변 유지, 기존 PaymentRequestedEvent 블록 유지.

## 검증 게이트 (수동)

- `scan` 후 Redis CLI: `GET qrflow:s2t:{sessionToken}` → tokenId 반환
- `scan` 후 Redis CLI: `GET qrflow:active:{tokenId}` → "1" 반환
- `initiate` 후 Redis CLI: `GET qrflow:intent:{tokenId}` → `{uuid}:{customerId}` 반환
