# domain/auth

OAuth2(Kakao) 로그인, JWT 발급/검증, Refresh 로테이션, PIN 인증, 회원가입 오케스트레이션.

## 하위 구조

```
auth/
├── controller/   AuthController, KakaoLogoutController, LoadTestController
│                 (SignupController는 signup/controller/ 하위)
├── cookie/       RefreshCookieManager, RefreshCookieProperties
├── enums/        AuthProvider, Gender, UserRole
├── handler/      OAuth2SuccessHandler
├── pin/
│   ├── model/        CustomerPinAuth (엔티티)
│   ├── repository/
│   └── service/      PinAuthService
├── security/
│   ├── JwtAccessDeniedHandler, JwtAuthenticationEntryPoint
│   ├── config/       SecurityConfig, SecurityConfigPerf, LoadTestSecurityConfig
│   ├── filter/       JwtAuthenticationFilter, LoadTestAuthenticationFilter,
│   │                 TestHeaderAuthenticationFilter, NoStoreAuthResponseFilter
│   └── principal/    UserPrincipal
├── signup/
│   ├── controller/   SignupController
│   ├── dto/
│   ├── service/      SignupFacade, SignupTxService
│   └── ticket/       SignupTicketService, SignupTicketPayload, SignupTicketPayloadFactory
└── token/            AccessTokenService, RefreshTokenService, JwtProperties
```

## 핵심 클래스

| 파일 | 역할 |
|---|---|
| `AccessTokenService` | JWT 발급(HS256, issuer `kakao-oauth2-jwt`), 파싱, 검증. Access TTL 15분 |
| `RefreshTokenService` | Refresh 발급/검증/로테이션. Redis 키 `auth:refresh:active:{role}:{userId}` (싱글세션). TTL 14일 |
| `PinAuthService` | 6자리 PIN 설정/변경/검증. 연속 5회 실패 시 5분 잠금 (`PIN_LOCKED` 423) |
| `CustomerPinAuth` | PIN 해시·실패횟수·잠금시간 저장 (낙관적 락 `@Version`) |
| `OAuth2SuccessHandler` | Kakao OAuth 성공 시 기존 회원 분기 (가입됨→토큰 발급, 미가입→회원가입 티켓 발급 후 프론트 이동) |
| `SignupFacade`/`SignupTxService` | 회원가입 오케스트레이션. 고객/점주 분기 + 중복 검증 + 지갑/PIN 초기화 |
| `SignupTicketService` | Redis에 10분 유효 티켓 저장. OAuth 후 프론트가 회원정보 수집 → 티켓 제시해서 가입 |
| `JwtAuthenticationFilter` | `Authorization: Bearer <JWT>` 파싱 → `UserPrincipal` → SecurityContext |
| `TestHeaderAuthenticationFilter` | `perf` 프로필 전용. `X-TEST-CUSTOMER-ID` 헤더로 인증 우회 |
| `LoadTestAuthenticationFilter` | `loadtest.backdoor.enabled=true` 시 `X-Test-User-Id`/`X-Test-Role` 헤더로 인증 |
| `NoStoreAuthResponseFilter` | 토큰 응답 경로에 `Cache-Control: no-store` 부여 |
| `SecurityConfig` | 필터 체인 + 역할 기반 인가. `SecurityConfigPerf`는 perf 프로파일 전용 |

## 도메인 규칙

- **Refresh 싱글세션**: 한 사용자당 활성 refresh 1개. 로테이션 시 직전 토큰 즉시 폐기.
- **회원가입 티켓 플로우**: Kakao 성공 → 미가입이면 10분 TTL 티켓(userinfo 포함) → 프론트가 폼 채움 → 티켓 제시해서 `/signup/...` 호출.
- **PIN 잠금**: 5회 실패 → 5분 잠금. 잠금 해제 시 실패 카운터 0.
- **주민번호 성별 파싱**: 1/3=남, 2/4=여.
- **Kakao 이메일 동의 선택**: 이메일 없으면 null 허용.
- **Nginx auth_request 대응**: `GET /auth/verify` 가 사용자 정보를 `X-User-Id`, `X-User-Role`, `X-Customer-Id`/`X-Owner-Id` 응답 헤더에 실어 보냄.

## 의존

- `domain.user.customer`, `domain.user.owner` (회원 조회/등록)
- `domain.wallet.WalletService` (회원가입 시 개인 지갑 생성)
- `global.exception`, `global.response`
- Redis (Refresh 저장, 회원가입 티켓)

## 엔드포인트

auth 도메인이 직접 노출하는 경로는 아래가 전부. charge·prepayment 관련 엔드포인트는 `domain/charge/CLAUDE.md` 참고.

| 메서드 | 경로 | 인증 | 비고 |
|---|---|---|---|
| POST | `/auth/refresh` | Refresh 쿠키 | Access 재발급 + Refresh 로테이션 |
| POST | `/auth/logout` | Access | Refresh 폐기 |
| GET | `/auth/verify` | Access | Nginx auth_request용. 응답 헤더로 사용자 정보 반환 |
| GET | `/auth/kakao/logout` | - | Kakao 로그아웃 URL 리다이렉트 |
| POST | `/signup/customer` | 회원가입 티켓 | 고객 등록 |
| POST | `/signup/owner` | 회원가입 티켓 | 점주 등록 |
| GET\|POST | `/oauth2/authorization/{kakao-customer\|kakao-owner}` | - | Kakao OAuth 시작 |
| * | `/api/loadtest/**` | backdoor | 부하테스트 전용 (`LoadTestController`) |

## 주의사항

1. Refresh 로테이션 재중복 호출: verify 이후 `active` 토큰과의 일치성 재확인 없으면 동일 구 토큰으로 여러 번 rotate 가능.
2. JWT 파싱 시 `ExpiredJwtException`, `JwtException` 구분 처리 필요 — 필터에서 별도 에러 메시지로 응답.
3. 중복 가입: 티켓 검증 후 `DataIntegrityViolationException`도 핸들링해야 완전 중복 방지.
4. `TestHeaderAuthenticationFilter`가 JwtAuthenticationFilter보다 먼저 실행 — 이미 인증되었으면 JWT 파싱 생략(의도적).
5. OAuth client-id/secret이 `application-{local,prod}.yml`에 하드코딩.
6. `ACCESS_TOKEN` 쿠키는 local 기본 `secure=false`. 운영(HTTPS)에서는 `secure=true`로 오버라이드해야 함.
