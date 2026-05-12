# qr-service / domain/idempotency

qr-service 전용 멱등성 키 관리. monolith `domain/idempotency`의 **독립 복제본**으로, 같은 철학·다른 테이블을 사용한다.

## 하위 구조

```
idempotency/
├── constant/    IdemStatus 등
├── dto/
├── model/       IdempotencyKey 엔티티
├── repository/
└── service/     IdempotencyService
```

## 핵심 규칙

- **키 형식**: `Idempotency-Key` 헤더, UUID 문자열. 없거나 형식 오류면 `IDEMPOTENCY_KEY_REQUIRED` / `_INVALID`.
- **본문 해시**: `@Qualifier("canonicalObjectMapper")` (config/ObjectMapperConfig) 로 정규화 직렬화 → SHA-256. 같은 키 + 다른 본문 = `IDEMPOTENCY_BODY_CONFLICT` (409).
- **상태**: `IN_PROGRESS` / `DONE`. IN_PROGRESS 중 동일 키 재요청 = 202 + `Retry-After: 2`.
- **응답 스냅샷**: JSON으로 저장. 완료된 키 재요청 = 200 OK + 스냅샷 재생 (`okReplay`). 최초 성공은 201.
- **적용 지점**: `POST /cpqr/{sessionToken}/initiate`, `POST /payments/{intentPublicId}/approve`, 그리고 내부적으로 `WalletClient.capture` 시 `UUID.nameUUIDFromBytes("capture:" + intentPublicId)` 결정적 키 전달.

## monolith와의 차이

- **별도 DB 스키마** (`payment_service`). monolith의 `idempotency_keys` 테이블과 공유 안 함.
- **정책은 동일** — 본문 정규화 방식, 해시 알고리즘, 상태 머신, 응답 스냅샷 재생까지 1:1 대응.
- monolith 쪽 변경 시 qr-service도 함께 갱신 고려.

## 주의사항

1. **ObjectMapper 혼동 금지**: 반드시 `canonicalObjectMapper` 빈 사용. 일반 Jackson 인스턴스와 직렬화 결과가 달라 해시 불일치.
2. **스냅샷 직렬화 실패**: `completeWithoutSnapshot` 폴백 — 이 경우 재요청은 DONE 로 인식되나 스냅샷 복원 불가.
3. **키 수명**: TTL 관리 로직은 서비스에 구현되어 있음. 외부에서 직접 레코드 삭제 금지.

## 교차 참조

- `domain/intent/CLAUDE.md` — 초기화·승인에서 어떻게 소비되는지.
- monolith `domain/idempotency/CLAUDE.md` — 원본 설계 문서.
- `common/exception` — `IDEMPOTENCY_*` 에러코드 enum.
