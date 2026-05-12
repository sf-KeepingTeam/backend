# domain/idempotency

결제·환불·포인트 공유 등 자금 이동 API의 멱등성 공통 인프라. monolith와 qr-service 양쪽에서 동일한 철학으로 사용(qr-service는 자체 복제 구현).

## 하위 구조

```
idempotency/
├── constant/
│   ├── IdemActorType.java  MERCHANT / CUSTOMER / SYSTEM
│   └── IdemStatus.java     IN_PROGRESS / DONE
├── dto/
│   └── IdemBegin.java
├── model/
│   ├── IdempotencyKey.java       (복합 유니크)
│   └── IdempotentResult.java     (HTTP status + body + replay 플래그 + retryAfter)
├── repository/
└── service/      IdempotencyService
```

## 핵심 클래스

| 파일 | 역할 |
|---|---|
| `IdempotencyKey` | 키 레코드. 유니크 제약: (`actorType`, `actorId`, `path`, `keyUuid`). 필드: `bodyHash`(SHA-256 32bytes), `status`, `httpStatus`, `responseJson`, `intentPublicId` |
| `IdempotencyService.beginOrLoad` | 선점(IN_PROGRESS 생성) 또는 기존 슬롯 로드 |
| `IdempotencyService.isBodyConflict` | 저장된 해시와 현재 해시 비교(`Arrays.equals`) |
| `IdempotencyService.complete` | DONE + 응답 JSON 스냅샷 저장. 직렬화 실패 시 `log.warn` |
| `IdempotencyService.completeCharge` | charge 도메인 전용 완료 처리 (직렬화 실패 시 warn 로깅 후 진행) |
| `IdempotencyService.completeStrict` | 직렬화 실패 시 예외 throw |
| `IdempotencyService.completeWithoutSnapshot` | httpStatus만 저장, 응답은 리소스 재조회로 재생 |
| `IdempotentResult` | 팩토리: `created(201)`, `ok(200)`, `okReplay(200)`, `accepted(202)`, `acceptedWithRetryAfterSeconds(n)` |

## 도메인 규칙

- **헤더 강제**: `Idempotency-Key` 없음/blank → `IDEMPOTENCY_KEY_REQUIRED`. 비-UUID → `IDEMPOTENCY_KEY_INVALID`.
- **본문 정규화**: 반드시 `@Qualifier("canonicalObjectMapper")`로 직렬화 후 SHA-256. 키 순서·공백 차이로 인한 오탐 방지.
- **빈 바디**: 환불처럼 body 없는 POST는 `sha256("")` 정적 상수 사용.
- **스코프**: path는 정규화해서 저장. 예) `/api/stores/{storeId}/transactions/{txId}/refund`.
- **재요청 동작**:
  - DONE + responseJson 있음 → 저장된 응답 복원 (200 OK, `okReplay`).
  - DONE + responseJson 없음 → 리소스 재조회로 응답 재구성.
  - IN_PROGRESS → 202 + `Retry-After: 2s`.
- **ActorType 구분**: 고객 포인트 공유 = CUSTOMER, 점주 환불 = MERCHANT. 스코프가 섞이면 안 됨.
- **복합 유니크 순서 유지**: DB 인덱스 최적화 목적.

## 의존

- `global.exception` (`IDEMPOTENCY_*` 코드)
- Jackson `ObjectMapper`(canonical 빈)
- `Clock` (주입 가능, 테스트성 확보)
- 역으로 `wallet`, `payment.refund`, `charge`, `internal.payment`, `internal.wallet` 이 이 서비스에 의존

## 엔드포인트

없음 (공통 서비스 라이브러리 역할).

## 주의사항

1. **정규화 누락**: 서비스 호출 전 `canonicalObjectMapper`로 반드시 정규화 후 해시. 안 하면 정상 재시도도 `BODY_CONFLICT`.
2. **빈 바디 해시 상수화**: 매 요청마다 `sha256("")` 재계산하지 말 것.
3. **직렬화 실패 처리**: `complete` vs `completeStrict` 용도 구분. 스냅샷 없는 DONE은 리소스 재조회 비용 발생.
4. **ActorType 혼동**: 점주 환불을 CUSTOMER로 스코프 찍으면 동일 키에서 충돌·누락 가능.
5. **`findByKeyString`** 사용 시 UUID 파싱 실패는 null 반환 — 호출처에서 null 체크.
6. **Retry-After 활용**: 202 응답 시 `acceptedWithRetryAfterSeconds(2)` 고정값 준수(클라이언트 구현 계약).
