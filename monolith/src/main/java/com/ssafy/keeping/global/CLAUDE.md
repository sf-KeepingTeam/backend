# global

도메인과 무관한 공통 인프라: 설정, 예외, 응답 포맷, 보안 필터 체인, S3, 유틸.

## 하위 구조

```
global/
├── config/       AsyncConfig, CanonicalJsonConfig, FirebaseConfig, PasswordConfig,
│                 RedisConfig, RestTemplateConfig, S3Config, SwaggerConfig, TimeConfig
├── constants/    HttpHeaderConstants (X-Internal-Auth, Idempotency-Key)
├── exception/    CustomException, GlobalExceptionHandler, constants/ErrorCode,
│                 constants/ExternalApiErrorMapper, dto/ExceptionDto
├── response/     ApiResponse, ExternalApiResponse, ExternalApiErrorResponse
├── s3/service/   ImageService
└── util/         TxUtils
```

## 핵심 클래스

| 파일 | 역할 |
|---|---|
| `ApiResponse<T>` | 표준 응답(`success/status/message/data/timestamp`) — 거의 모든 Controller 반환값 |
| `ErrorCode` (enum) | 전 도메인의 비즈니스 에러 정의. HTTP 상태 + 한국어 메시지 |
| `CustomException` | `ErrorCode` 래퍼. 서비스 전역 비즈니스 예외 |
| `GlobalExceptionHandler` | `@RestControllerAdvice`. CustomException/Validation/기타 → `ExceptionDto` |
| `CanonicalJsonConfig` | `canonicalObjectMapper` 빈 — 멱등성 해시용 정규화 ObjectMapper. 키 정렬 등 |
| `RedisConfig` | `StringRedisTemplate` 빈 설정 (범용 `RedisTemplate` 빈은 별도 정의 없음 — Spring Boot 기본값 사용) |
| `RestTemplateConfig` | 공용 `RestTemplate` 빈 1개 (connectTimeout 5s / readTimeout 10s). Toss, Clova, 내부 서비스 호출 등에서 공통 사용. qr-service는 별도 `RestTemplateConfig`에서 read/write/recovery 3종 분리하지만, monolith는 단일 빈만 제공 |
| `S3Config` / `ImageService` | AWS S3 이미지 업로드. 메서드: `uploadImage(MultipartFile, kindOfImage)` / `updateProfileImage(String oldUrl, MultipartFile file)` |
| `FirebaseConfig` | `@PostConstruct initialize()`로 FCM 초기화. **파일 부재 시 기동 실패가 아니라 `warn` 로그만 남기고 정상 기동** (graceful degradation) |
| `SwaggerConfig` | OpenAPI / Springdoc |
| `AsyncConfig` | `webhookExecutor` 빈 정의 (monolith → qr-service webhook용). 일반 비동기용 executor는 별도 없음 — `@Async` 사용처별로 확인 필요 |
| `TimeConfig` | `Clock` 빈(테스트 주입용) |
| `PasswordConfig` | `PasswordEncoder` 빈 1개. PIN 해시뿐 아니라 모든 비밀번호성 데이터에 공용 사용 가능 |
| `TxUtils` | 트랜잭션 경계 유틸 |
| `ExternalApiErrorMapper` | SSAFY 금융망 등 외부 API 오류 코드 → `ErrorCode` 매핑 |

## 공통 규칙

- **응답**: Controller는 가능한 한 `ApiResponse.success(...)` / `ApiResponse.error(...)` 반환.
- **예외**: 비즈니스 예외는 `throw new CustomException(ErrorCode.X)`. 런타임 예외/검증 예외는 `GlobalExceptionHandler`가 포맷팅.
- **외부 API 래퍼**: SSAFY 금융망 호출처는 `ExternalApiResponse`/`ExternalApiErrorResponse` 사용.
- **Canonical JSON**: 멱등성 해시 대상 Body는 `@Qualifier("canonicalObjectMapper")`로 직렬화.
- **Clock 주입**: 시간 의존 서비스는 `Clock` 빈 사용 — 테스트에서 `Clock.fixed(...)` 교체.
- **이미지 업로드**: 모든 도메인이 `ImageService.upload(MultipartFile, path)` / `updateProfileImage(...)` 사용.

## 엔드포인트

없음 (인프라 레이어).

## 주의사항

1. 새 에러는 반드시 `ErrorCode`에만 추가. 도메인별 에러 enum 별도 생성 금지.
2. `canonicalObjectMapper`를 다른 용도로 쓰지 말 것 — 멱등성 해시 전제에 맞춘 설정.
3. `RestTemplate` 공용 빈은 타임아웃이 일반값 — 토스·Clova처럼 타임아웃 민감한 호출은 전용 설정을 쓸 것(특히 qr-service는 read/write/recovery 3종 분리).
4. `FirebaseConfig` 초기화 — `classpath:firebase/keeping-firebase-adminsdk.json` 파일이 없으면 **기동은 정상 진행**하되 `warn` 로그만 남고 FCM 기능이 비활성화됨(graceful). 부하테스트 등에서는 이 경로 파일만 생략해도 서비스가 뜬다.
5. `GlobalExceptionHandler`가 특정 예외를 누락하면 500 Internal로 나감 — 신규 Spring 예외 타입 추가 시 핸들러 확인.
6. `ImageService` 메서드 호출 시 두 번째 인자는 경로(path)가 아니라 **이미지 종류(kindOfImage, 예: `"store"`, `"menu"`)** — 네이밍 주의.
7. `GlobalExceptionHandler`는 `ApiResponse` 포맷으로 응답한다. `ExceptionDto`는 JWT 인증 실패 응답(`JwtAuthenticationEntryPoint`/`JwtAccessDeniedHandler`)에서 사용 중.
8. 비표준 헤더(`X-Internal-Auth`, `Idempotency-Key`) 사용 시 `HttpHeaderConstants` 상수 참조 — 문자열 직접 입력 금지.
