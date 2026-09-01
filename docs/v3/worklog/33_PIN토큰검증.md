## 세트 #33 — PIN 토큰 검증 (qr)

### 세트 #32 계약과의 대조

| 클레임 | #32가 넣는다고 한 것 | #33이 읽는 것 | 일치 |
|---|---|---|---|
| `iss` | `"keeping-pin-token"` | `requireIssuer("keeping-pin-token")` | O |
| `sub` | `String.valueOf(customerId)` | `Long.parseLong(claims.getSubject())` | O |
| `intentPublicId` | 커스텀 클레임, `intent.getPublicId().toString()` | `claims.get("intentPublicId", String.class)` → `intentPublicId.toString()`과 대조 | O |
| `jti` | `UUID.randomUUID().toString()` | `claims.getId()` → Redis SETNX 기록 | O |
| `iat` | `Instant.now()` | 직접 읽지 않음 (jjwt 내부에서 참조) | O |
| `exp` | `iat + tokenTtlSeconds` | jjwt 파서가 자동 검증 (`ExpiredJwtException`) | O |
| 서명 알고리즘 | HMAC-SHA256, `Keys.hmacShaKeyFor()` | 동일: `Keys.hmacShaKeyFor(secret.getBytes(UTF_8))` | O |
| 서명 키 | `JwtProperties.secret()` (= `JWT_SECRET`) | 동일: `JwtProperties.secret()` | O |

### 검증 순서

1. **서명** — `Jwts.parser().verifyWith(key)` → `JwtException` 시 `PIN_TOKEN_INVALID`
2. **exp** — jjwt 파서 자동 처리 → `ExpiredJwtException` 시 `PIN_TOKEN_EXPIRED`
3. **issuer** — `requireIssuer("keeping-pin-token")` → 불일치 시 `PIN_TOKEN_INVALID` (jjwt가 `IncorrectClaimException` 던짐, `JwtException`으로 catch)
4. **intentPublicId 대조** — 토큰의 `intentPublicId` 클레임과 요청 경로의 `intentPublicId`를 문자열 비교 → 불일치 시 `PIN_TOKEN_INTENT_MISMATCH`
5. **jti 재사용 확인** — Redis `SETNX` → 이미 존재하면 `PIN_TOKEN_REUSED`

### jti 저장

- **Redis 키 형식**: `pin-token:jti:{jti}` (예: `pin-token:jti:550e8400-e29b-41d4-a716-446655440000`)
- **TTL**: `tokenTtlSeconds + 30` = 90초 (기본)
- **왜 그 TTL인가**: 토큰 수명(60초)보다 30초 길게 잡아 시계 오차를 보상한다. 토큰이 만료된 뒤에도 30초간 jti 기록을 유지하여, 만료 직전에 발급된 토큰의 재사용을 확실히 차단한다. TTL이 지나면 Redis가 자동 삭제하므로 메모리 누수 없음.
- **원자성**: `setIfAbsent` (Redis `SETNX`) 한 번으로 "기록 + 중복 확인"을 원자적으로 수행. Redis 왕복 1회.

### 병행 지원

- **분기 지점**: `PaymentIntentService.java`
  - `approveSplit()` 내 `[NO-TX] PIN 검증` 구간 (약 478행): `useTokenPath` boolean으로 분기
  - `approveLegacy()` 내 PIN 검증 구간 (약 388행): `useTokenPathLegacy` boolean으로 분기
  - `approve()` 입력 검증 (약 299행): `hasPin || hasToken` 둘 다 없으면 `PIN_REQUIRED`
- **token-enabled=false 동작**: `PaymentTuningProperties.pin.tokenEnabled`가 `false`이면 `useTokenPath`/`useTokenPathLegacy`가 항상 `false` → 토큰이 제출되어도 무시하고 기존 PIN HTTP 경로로 처리

### 세트 #31 규약 준수

- **토큰 검증이 [NO-TX] 구간에 있음**:
  - `approveSplit()`: TX-A(`prepareApproval`) 커밋 후, TX-B(`finalizeApproved`) 진입 전의 [NO-TX] 구간에서 `pinTokenVerifier.verify()` 호출
  - `approveLegacy()`: 단일 트랜잭션 내부이지만 토큰 검증 자체는 순수 계산 + Redis I/O만 수행 (JPA 미사용)
- **그 구간 JPA 리포지토리 호출 0건**: `PinTokenVerifier.java`에는 JPA 리포지토리 의존성이 없다. 주입받는 것은 `JwtProperties`, `PaymentTuningProperties`, `StringRedisTemplate` 뿐.

### 변경 파일

| 파일 | +/- | 무엇을 왜 |
|---|---|---|
| `common/exception/ErrorCode.java` | +4줄 | `PIN_TOKEN_INVALID`, `PIN_TOKEN_EXPIRED`, `PIN_TOKEN_INTENT_MISMATCH`, `PIN_TOKEN_REUSED` 추가 |
| `domain/intent/dto/ApproveRequest.java` | 수정 | `pinToken` 필드 추가, `@NotBlank`/`@Pattern` 제거 (토큰 경로에서 Bean Validation 차단 방지) |
| `domain/intent/canonical/CanonicalApprove.java` | +1필드 | `pinToken` 필드 추가 (토큰 경로 정규화용) |
| `domain/intent/service/PinTokenVerifier.java` | +신규 | JWT 서명/만료/issuer 검증 + intentPublicId 대조 + jti Redis SETNX |
| `domain/intent/service/PaymentIntentService.java` | 수정 | `PinTokenVerifier` 주입, `approve()` 입력 검증 분기, `approveSplit`/`approveLegacy` 토큰 경로 추가, `canonicalizeApproveBody` 토큰 경로 분기 |
| `config/PaymentTuningProperties.java` | 변경 없음 | 세트 #30이 생성, `pin.tokenEnabled`/`pin.tokenTtlSeconds` 이미 존재 |
| `src/test/.../PinTokenVerifyTest.java` | +신규 | K-1~K-8 테스트 (작성만, 실행 금지) |

### 계약 준수

- [x] `customerClient.verifyPin` 삭제 안 됨 (기존 PIN 경로 그대로 유지)
- [x] `PaymentStatus` enum 변경 없음
- [x] 토큰 검증은 [NO-TX] 구간, JPA 리포지토리 호출 0건
- [x] 관측 지표 변경 없음
- [x] monolith 파일 수정 없음
- [x] 새 시크릿/JWT 인프라 생성 없음 (기존 `JWT_SECRET` 재사용)

### 컴파일 안전 자가점검

- [x] `PinTokenVerifier` → `JwtProperties.secret()`: record 메서드명 일치
- [x] `PinTokenVerifier` → `PaymentTuningProperties.getPin().getTokenTtlSeconds()`: Lombok getter 일치
- [x] `PinTokenVerifier` → `StringRedisTemplate.opsForValue().setIfAbsent()`: Spring Data Redis API 일치
- [x] `PinTokenVerifier` → `Jwts.parser().verifyWith(key).requireIssuer()`: jjwt 0.12.3 API 일치
- [x] `ApproveRequest.getPinToken()`: Lombok `@Getter` 생성
- [x] `CanonicalApprove.builder().pinToken()`: Lombok `@Builder` 생성
- [x] `PaymentIntentService` 생성자에 `PinTokenVerifier` 추가: 파라미터 순서 확인
- [x] `ErrorCode.PIN_TOKEN_*` 4개: enum에 추가 확인
- [x] import 경로 모두 실제 패키지와 일치

### 정직하게 — 토큰화가 없애는 것과 못 없애는 것

**없애는 것**: qr-service의 `approve()` 임계 경로에서 monolith PIN 검증 HTTP 왕복 1회 (627ms). 토큰 경로에서는 로컬 JWT 검증(~1ms) + Redis SETNX(~1ms)로 대체. qr 스레드가 monolith 응답을 기다리며 붙잡혀 있는 시간이 사라진다.

**못 없애는 것**: monolith 의존 자체. 고객은 여전히 monolith에 PIN을 보내 토큰을 발급받아야 한다. monolith가 죽으면 토큰을 받을 수 없고, 결제도 안 된다. 단, 이 호출은 qr approve 트랜잭션 바깥(프론트 → monolith 직접)에서 일어나므로 qr-service의 Hikari 커넥션 풀/워커 스레드 점유에는 영향을 주지 않는다. monolith→qr 방향 HTTP 호출이 3회→2회로 줄어든 것이지 0회가 된 것이 아니다 (자금 캡처 호출은 여전히 남아 있다).

### 인프라_인계.md 에 추가한 항목

- 세트 #33 섹션: `payment.pin.token-enabled` 설정 키, Redis jti 키 형식/TTL, 재측정 확인 사항, Grafana 지표 권장, 변경 영향 범위

### 가정 / 확인하지 못한 것

1. **jjwt 0.12.5(monolith)와 0.12.3(qr-service)의 토큰 호환성을 가정.** 동일 메이저 버전이고 HMAC-SHA256 + 표준 클레임만 사용하므로 호환 문제 없을 것이다. 통합 테스트로 확인 필요.
2. **Redis 가용성을 가정.** jti 기록에 Redis를 사용하므로, Redis 장애 시 `setIfAbsent` 호출이 예외를 던져 결제가 실패한다. 이는 의도된 동작이다 — jti 검증을 건너뛸 수 없다 (토큰 재사용 공격 방지).
3. **`ApproveRequest`에서 `@NotBlank`/`@Pattern` 제거.** 기존에 Spring Bean Validation이 PIN 형식을 검증했으나, 토큰 경로에서 PIN이 null일 수 있으므로 서비스 계층 검증으로 이동했다. `canonicalizeApproveBody`에서 PIN 경로 진입 시 동일한 `\d{6}` 검증을 수행하므로 보안 수준은 동일. `PaymentApprovalController`의 `@Valid`는 제약 어노테이션 없이 no-op이 되며, `@Validated`는 qr-service 어디에도 사용되지 않는다.
4. **`fixedClock` 기반 테스트.** 테스트에서 `Clock.fixed`를 사용해 토큰 생성/검증 시각을 제어한다. 실제 환경에서는 monolith와 qr-service의 시계가 30초 이상 어긋나면 jti TTL과 exp 검증에 문제가 생길 수 있다. NTP 동기화 확인 필요.
5. **`token-enabled=false` + 토큰만 제출(PIN 없음) 시 동작.** 현재는 `approve()` 입력 검증에서 `hasPin || hasToken`이면 통과 → approveSplit/approveLegacy에서 `tokenEnabled=false`이므로 PIN 경로 진입 → `customerClient.verifyPin(customerId, null)` 호출 → monolith가 false/에러 반환 → DECLINED. 프론트가 `tokenEnabled=false` 상태에서 토큰만 보내는 시나리오는 정상 사용에서 발생하지 않으나, 방어적으로 `approve()` 입력 검증에서 조기 거부(`PIN_REQUIRED`)하는 것이 더 깔끔할 수 있다. 현재는 monolith 판단에 위임.
6. **`canonicalizeApproveBody`의 토큰/PIN 분기와 `tokenEnabled` 플래그의 독립성.** `canonicalizeApproveBody`는 `req.getPinToken()` 유무만 보고 정규화 경로를 결정한다 (`tokenEnabled` 무관). 따라서 `tokenEnabled=false` + 토큰 제출 시 멱등성 해시는 토큰 기반이지만 검증은 PIN 기반이 된다. 동일 멱등 키로 재시도 시 본문이 동일하면 정상 재생되고, 다르면 `IDEMPOTENCY_BODY_CONFLICT`로 거부된다. 의도된 동작.
