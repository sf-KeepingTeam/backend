---
name: add-error-code
description: Use when the user adds a new business error that should raise a CustomException — add an entry to the ErrorCode enum and verify GlobalExceptionHandler coverage. Trigger phrases include "에러 코드 추가", "새 예외", "ErrorCode 하나 만들어줘".
---

# Skill: add-error-code

모든 비즈니스 예외는 `global.exception.constants.ErrorCode` enum **한 곳**에서 정의한다. 이 스킬은 새 에러 추가 시 누락 없이 처리되도록 보장한다.

## 규칙
1. ErrorCode enum **외부에 따로 예외 클래스를 만들지 않는다** — `throw new CustomException(ErrorCode.XXX)` 사용.
2. 이름 규칙: `DOMAIN_SUBJECT_CAUSE`. 예: `PAYMENT_INTENT_EXPIRED`, `IDEMPOTENCY_BODY_CONFLICT`, `QR_STORE_MISMATCH`.
3. HTTP 상태 매핑은 enum 인자로 명시 (`HttpStatus.XXX`).
4. 메시지는 **한국어**. 사용자에게 그대로 노출될 수 있음을 가정.

## 체크리스트

### ① ErrorCode enum 추가
`monolith/src/main/java/com/ssafy/keeping/global/exception/constants/ErrorCode.java`  
(qr-service 쪽 추가라면 `qr-service/.../common/exception/ErrorCode.java` — 동일 정책으로 복제)

- [ ] 네이밍 중복 없는지 grep 확인
- [ ] 관련 도메인 에러들과 **근처에 묶어** 배치 (섹션별)

### ② GlobalExceptionHandler 확인
`monolith/.../global/exception/GlobalExceptionHandler.java`

- [ ] `CustomException` 전체를 ErrorCode 기반으로 응답하므로 **대부분 자동 처리됨**
- [ ] 다만 외부 API 예외 (`ExternalApiErrorMapper`)나 특수 예외 타입을 매핑하려면 별도 @ExceptionHandler 필요

### ③ 호출부
- [ ] `throw new CustomException(ErrorCode.XXX)` 형태로 일관
- [ ] 디버깅 컨텍스트(파라미터 등)가 필요하면 `CustomException(ErrorCode.XXX, detail)` 패턴 사용 여부 확인

### ④ ApiResponse 형태 확인
- [ ] 전역 핸들러가 `ApiResponse.fail(errorCode)` 로 래핑 — 프론트 파싱 스펙에 맞는지 점검

### ⑤ qr-service 동기화
- [ ] 결제·멱등성·인증처럼 **양 서비스 공통**이면 qr-service `ErrorCode` 에도 동일 심볼 추가 (두 서비스 enum이 별개)
- [ ] 공통 심볼은 **코드 값도 동일**하게 유지 (가능하면 HTTP status도)

## 안티 패턴
- `RuntimeException`, 또는 도메인 전용 커스텀 예외 클래스 신설 → **금지**. CustomException + enum만.
- 영어 메시지 → 프론트 노출 시 어색.
- HTTP 500 남발 → 비즈니스 실패는 4xx 계열을 우선 고려.

## 참고
- 응답 포맷: `global.response.ApiResponse`
- 외부 API 에러 래핑: `global.response.ExternalApiErrorResponse`, `ExternalApiErrorMapper`
- 도메인 에러 예시: `IDEMPOTENCY_*`, `QR_*`, `PAYMENT_INTENT_*`, `FUNDS_*`, `PIN_LOCKED`
