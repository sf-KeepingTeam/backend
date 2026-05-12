---
name: idempotency-inject
description: Use when the user is adding a new payment/funds/refund/point-share API endpoint (anything that mutates money) and needs the standard Idempotency-Key boilerplate wired in. Trigger phrases include "새 결제 API", "idempotency 적용", "멱등성 붙여줘". Applies to both monolith and qr-service.
---

# Skill: idempotency-inject

새 결제 계열 API (자금 캡처/환불/포인트 공유/선결제 승인/PaymentIntent initiate·approve) 작성 시 **Idempotency-Key 처리 boilerplate를 일관되게 주입**한다.

## 언제 사용하는가
- 돈이 움직이거나 외부 PG를 호출하거나 결제 상태가 전이되는 API 추가/수정 시.
- 프론트 재시도나 네트워크 재전송이 발생할 수 있는 엔드포인트.

## 사용 전 필독
1. 도메인 CLAUDE.md: `monolith/.../domain/idempotency/CLAUDE.md` (원본 정책)
2. qr-service 쪽이면: `qr-service/.../domain/idempotency/CLAUDE.md` (독립 복제본, 동일 정책)
3. 응답 코드 규칙 (최초 201 / replay 200 / in-progress 202 + Retry-After)

## 주입 체크리스트

### ① 컨트롤러
- [ ] `@RequestHeader("Idempotency-Key") String idempotencyKey` 파라미터 추가
- [ ] UUID 검증 실패 → `IDEMPOTENCY_KEY_INVALID`
- [ ] 누락 → `IDEMPOTENCY_KEY_REQUIRED` (`CustomException`)

### ② 서비스 진입
- [ ] `IdempotencyService.begin(actor, key, canonicalBody)` 호출
- [ ] Body 직렬화는 **반드시** `@Qualifier("canonicalObjectMapper")` 주입된 ObjectMapper 사용
- [ ] 동일 키 · 다른 본문 → `IDEMPOTENCY_BODY_CONFLICT` (409)
- [ ] 결과가 DONE → 저장된 스냅샷 그대로 재생 (`okReplay`, 200)
- [ ] 결과가 IN_PROGRESS → 202 + `Retry-After: 2` 응답

### ③ 서비스 출구
- [ ] 성공 시 `IdempotencyService.complete(key, responseDto)` — JSON 스냅샷 저장
- [ ] 스냅샷 직렬화 실패 시 `completeWithoutSnapshot` 폴백
- [ ] 실패 시 상태 업데이트(DONE_FAILED 유사) 또는 키 해제 — 도메인 CLAUDE.md 기준

### ④ 외부 호출 멱등성
- [ ] 자금 캡처 등 외부 호출 시 **결정적 키** 전달:  
      `UUID.nameUUIDFromBytes(("capture:" + intentPublicId).getBytes(UTF_8))`
- [ ] 호출 측이 `@Transactional` 안이면 타임아웃 침범 주의 → 락 해제 후 호출하거나 별도 트랜잭션 분리

### ⑤ 테스트
- [ ] 동일 키 두 번 호출 → 200 replay 검증
- [ ] 동일 키 다른 본문 → 409 검증
- [ ] IN_PROGRESS 상태 → 202 + Retry-After 검증

## 흔한 실수
- 일반 `ObjectMapper` 사용 (필드 정렬/공백 달라져 해시 불일치) → 반드시 canonical.
- 최초 201 만 처리하는 프론트 → replay 200 도 성공으로 처리하라고 명세 기재.
- 외부 호출 후 응답 스냅샷 저장 전에 예외 → `completeWithoutSnapshot` 경로로 DONE 처리.
- Intent/Transaction 낙관락 충돌을 `IDEMPOTENCY_*`가 아니라 `PAYMENT_*` 계열로 반환 — 구분 유지.

## 참고 에러코드
`IDEMPOTENCY_KEY_REQUIRED`, `IDEMPOTENCY_KEY_INVALID`, `IDEMPOTENCY_BODY_CONFLICT`, `IDEMPOTENCY_REPLAY_UNAVAILABLE`.

## 교차 문서
- `monolith/.../domain/payment/CLAUDE.md`, `domain/wallet/CLAUDE.md`, `domain/charge/CLAUDE.md`
- qr-service: `domain/intent/CLAUDE.md`
