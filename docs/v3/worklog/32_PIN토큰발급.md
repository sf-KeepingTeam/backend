## 세트 #32 — PIN 검증 토큰 발급

### 토큰 설계

- **클레임 구성**:

| 클레임 | JWT 필드 | 타입 | 설명 |
|---|---|---|---|
| issuer | `iss` | String | `"keeping-pin-token"` (access token의 `"kakao-oauth2-jwt"`와 구별) |
| subject | `sub` | String | customerId를 문자열로 변환 (`String.valueOf(customerId)`) |
| intent | `intentPublicId` | String | 결제 요청(intent)의 publicId. **커스텀 클레임** |
| jti | `jti` | String | `UUID.randomUUID().toString()` — 1회용 식별자. **기록은 qr-service(세트 #33)가 담당** |
| issued at | `iat` | Date | 발급 시각 (`Instant.now()`) |
| expiration | `exp` | Date | `iat + tokenTtlSeconds` |

- **서명 알고리즘**: HMAC-SHA256 (jjwt 라이브러리의 `Keys.hmacShaKeyFor()`)
- **서명 키**: `JwtProperties.secret()` — 두 서비스가 이미 공유하는 `JWT_SECRET`. 새 시크릿 없음.
- **TTL**: 기본 60초. 설정 키: `payment.pin.token-ttl-seconds` (`@Value`로 주입)
- **issuer 분리 이유**: qr-service가 PIN 토큰과 access token을 구별해야 한다. 파싱 시 `requireIssuer("keeping-pin-token")`으로 타입 검증.

### qr(세트 #33)이 검증할 때 필요한 계약

```
1. 토큰은 JWT(JWS) 형식, HMAC-SHA256 서명
2. 서명 키: JWT_SECRET (JwtProperties.secret()) UTF-8 바이트 → Keys.hmacShaKeyFor()
3. 파싱 시 requireIssuer("keeping-pin-token") 필수
4. sub 클레임 = customerId (String) — Long.parseLong(claims.getSubject())로 복원
5. "intentPublicId" 커스텀 클레임 = intent의 publicId (String)
   → approve 요청의 intentPublicId와 반드시 일치 검증할 것
6. jti 클레임 = UUID 문자열 — 1회용 보장을 위해 qr-service가 사용 후 기록(Redis 등)
7. exp 클레임으로 만료 검증 — jjwt 파서가 자동 처리 (ExpiredJwtException)
8. 파싱 실패 시(만료, 서명 불일치, issuer 불일치 등) → 결제 거부, monolith 재호출 불필요
```

**jjwt 버전**: monolith 0.12.5 / qr-service 0.12.3 — 호환됨 (동일 API).

### §4-6 결정 — PinAuthService UPDATE

- **`last_verify_at` 사용처 grep 결과**:
  - `CustomerPinAuth.java:40-41` — 필드 선언
  - `PinAuthService.java:45` — 초기화 시 null 설정
  - `PinAuthService.java:108` — (수정 전) 성공 시 갱신
  - `keeping.sql:54` — DDL 정의
  - **읽는 곳: 없음.** 어떤 서비스·컨트롤러·쿼리도 `lastVerifyAt`/`last_verify_at`을 조회하지 않는다.

- **채택한 안: (a) — 실패 시에만 기록**
  - 성공 시 `failedCount`가 이미 0이고 `lockedUntil`이 null이면 `save()` 호출을 생략한다.
  - `lastVerifyAt` 갱신도 제거한다 (읽는 곳 없음).
  - **근거**:
    1. `lastVerifyAt`는 감사 목적이지만 실제로 어디서도 읽지 않으므로 갱신 가치가 없다.
    2. 토큰화에 의해 호출 빈도가 "결제당 1회 → 세션당 1회"로 줄어들지만, 그 1회마저도 불필요한 UPDATE를 피하면 MySQL 쓰기 부하가 추가 감소한다.
    3. 향후 `lastVerifyAt`이 필요해지면 (감사 보고서 등) 그때 복원하면 된다. 컬럼과 엔티티 필드는 그대로 유지했으므로 코드 한 줄 복원으로 충분하다.

### 변경 파일

| 파일 | +/- | 무엇을 왜 |
|---|---|---|
| `domain/auth/pin/controller/PinTokenController.java` | +신규 | `POST /customers/pin/verify-token` 엔드포인트. 인증된 고객(CUSTOMER)만 접근. SecurityContext에서 customerId 추출 |
| `domain/auth/pin/dto/PinTokenRequest.java` | +신규 | 요청 DTO: `pin`(String) + `intentPublicId`(String) |
| `domain/auth/pin/dto/PinTokenResponse.java` | +신규 | 응답 DTO: `pinToken`(String) |
| `domain/auth/pin/service/PinTokenService.java` | +신규 | PIN 검증 위임 + JWT PIN 토큰 발급. `PinAuthService.verify()` 호출 후 성공 시 토큰 빌드 |
| `domain/auth/pin/service/PinAuthService.java` | 수정 | §4-6(a): 성공 경로에서 `failedCount==0 && lockedUntil==null`이면 `save()` 생략. `lastVerifyAt` 갱신 제거 |
| `global/exception/constants/ErrorCode.java` | +1줄 | `INTENT_PUBLIC_ID_REQUIRED(BAD_REQUEST, "결제 요청 ID(intentPublicId)는 필수입니다.")` |
| `src/main/resources/application.yml` | +2줄 | `payment.pin.token-ttl-seconds: 60` 추가 |
| `test/.../pin/service/PinTokenServiceTest.java` | +신규 | P-1~P-6 테스트 (Mockito 단위 테스트, 실행 금지) |
| `test/.../pin/service/PinAuthServiceUpdateOptTest.java` | +신규 | P-7 테스트: §4-6(a) UPDATE 생략 검증 (실행 금지) |

### 계약 준수

- **Argon2 파라미터 변경**: 없음. `PasswordConfig.java`의 `Argon2PasswordEncoder(16, 32, 1, 1<<13, 3)` 그대로.
- **잠금 정책 유지**: `PinAuthService.java:86-88` (잠금 체크), `:92-102` (실패 카운트 증가 + 잠금 설정). 변경 없음.
- **기존 pin-verify 엔드포인트 유지**: `InternalCustomerController.java:66-84` (`POST /internal/customers/{customerId}/pin-verify`). 수정 없음.
- **qr-service 파일 수정**: 없음
- **v2 원장 작업 영역(domain/wallet, domain/payment) 수정**: 없음
- **새 시크릿 생성**: 없음. `JwtProperties.secret()` 공유.

### 정직하게 — 토큰화가 없애는 것과 못 없애는 것

**없애는 것**: qr-service의 `approve()` 과정에서 monolith에 PIN 검증을 요청하며 기다리는 시간(627ms). qr 스레드가 monolith 응답을 기다리며 붙잡혀 있는 시간이 사라진다. 토큰 서명 검증은 로컬에서 ~1ms 미만.

**못 없애는 것**: monolith 의존 자체. 토큰 발급을 위해 고객은 여전히 monolith에 PIN을 보내야 한다. monolith가 죽으면 토큰을 받을 수 없고, 결제도 안 된다. 단, 이 호출은 결제 임계 경로(qr approve 트랜잭션) 바깥에서 일어나므로 qr-service의 커넥션 풀/스레드 점유에는 영향을 주지 않는다.

### 컴파일 안전 자가점검

- [x] `PinTokenService` → `PinAuthService.verify(Long, String)`: 시그니처 일치 확인
- [x] `PinTokenService` → `JwtProperties.secret()`: record 메서드명 일치 확인
- [x] `PinTokenController` → `UserPrincipal.id()`: record 메서드명 일치 확인
- [x] `PinTokenController` → `PinTokenRequest.getPin()`, `.getIntentPublicId()`: Lombok getter 일치 확인
- [x] `PinTokenResponse.of(String)`: static factory 존재 확인
- [x] `ErrorCode.INTENT_PUBLIC_ID_REQUIRED`: enum에 추가 확인
- [x] `ApiResponse.success(String, int, T)`: 시그니처 일치 확인
- [x] import 경로 모두 실제 패키지와 일치

### 인프라_인계.md 에 추가한 항목

해당 없음 (인프라_인계.md 파일이 존재하지 않음. 필요시 인프라 담당이 생성).

### 가정 / 확인하지 못한 것

1. **`@AuthenticationPrincipal UserPrincipal`이 CUSTOMER 요청에서 정상 주입된다고 가정.** `JwtAuthenticationFilter`가 `SecurityContextHolder`에 `UserPrincipal`을 넣는 것은 기존 코드(`/customers/**` 엔드포인트들)에서 이미 사용 중이므로 동일하게 동작할 것이다. 컴파일은 확인했으나 런타임은 미검증.
2. **jjwt 0.12.5(monolith)와 0.12.3(qr-service)의 토큰 호환성을 가정.** 동일 메이저 버전이고 HMAC-SHA256 + 표준 클레임만 사용하므로 호환 문제 없을 것이다. 인프라 담당이 통합 테스트로 확인 필요.
3. **`payment.pin.token-ttl-seconds` 설정이 monolith의 `application.yml`에만 있으면 충분하다고 가정.** qr-service는 이 값을 몰라도 된다 — 토큰의 `exp` 클레임으로 만료를 판단하므로.
