# Keeping Backend

선결제(포인트) 기반 소상공인 결제 플랫폼. 고객은 매장에 포인트를 선결제하고, 개인/모임 지갑으로 관리하며 QR로 결제한다.

## 아키텍처

두 개의 Spring Boot 서비스 + Nginx 게이트웨이.

```
[Client] → [Nginx :80] → ┬── monolith :8080 (대부분의 API)
                          └── qr-service :8082 (/api/qr, /cpqr/*/initiate,
                                                /payments/*/approve,
                                                /api/payments/intent/*)
              ↑↓ (내부 호출: X-Internal-Auth 헤더 필수)
        monolith ↔ qr-service (REST, Webhook)
```

- **monolith** (`monolith/src/main/java/com/ssafy/keeping/`): 인증, 회원, 매장/메뉴, 선결제, 지갑, 알림, 통계, OCR, 정산
- **qr-service** (`qr-service/src/main/java/com/ssafy/keeping/qr/`): QR 토큰 발급, CPQR 결제 의도 생성/승인, 결제 복구 스케줄러
- **Nginx** (`gateway/nginx.conf`): API Gateway 역할 — 경로 기반 라우팅, `/internal/*` 외부 차단, 인증이 필요 없는 경로(`/auth/login`, `/oauth2`, `/login/oauth2`, `/api/loadtest`) 분기.
  - **주의**: 현재 conf는 `listen 80`만 있고 TLS 종료 없음. HTTPS는 상위 LB/ALB에서 처리하는 구조로 보임.
- **공유 DB (컨테이너 기준)**: 하나의 MySQL 인스턴스에 두 서비스가 각기 다른 논리 DB(`keeping` / `payment_service`)로 접속. Redis도 공유.

## 기술 스택

| 구분 | 버전/도구 |
|---|---|
| Language / Runtime | Java 21 (monolith), Java 17 (qr-service build.gradle 기준, Docker는 temurin:21-jre) |
| Framework | Spring Boot 3.5.5, Spring Cloud 2024.0.0 |
| Persistence | Spring Data JPA, MySQL 8, H2(테스트), Testcontainers |
| Cache / Session | Redis 7, Spring Data Redis |
| Auth | Spring Security, OAuth2 Client (Kakao), jjwt **0.12.5 (monolith)** / **0.12.3 (qr-service)**, BouncyCastle |
| 외부 연동 | Toss Payments, Firebase Admin (FCM), AWS S3 (awspring 3.1.1), Clova OCR, OpenAI |
| 통신 | Spring WebFlux(WebClient), Apache HttpClient5, RestTemplate |
| 관측 | Micrometer + Prometheus + Brave Tracing, Zipkin reporter (리포트 비활성) |
| 테스트 | JUnit 5, Spring Cloud Contract 4.1.4, Testcontainers (mysql) |
| 기타 | Springdoc OpenAPI **2.7.0 (monolith)** / **2.3.0 (qr-service)**, Spring Retry |

qr-service는 추가로 **Resilience4j**(Circuit Breaker + Retry)를 사용한다.

## 실행 방법

### 로컬 개발 (profile=local, IDE에서 실행)

사전 요구:
- 로컬 MySQL: `localhost:3306`, user=`root`/pw=`ssafy`, DB=`keeping`
- 로컬 Redis: `localhost:6379`

실행 (각 서비스는 독립 Gradle 프로젝트, 각자 디렉토리에서 기동):
```bash
# monolith
(cd monolith && ./gradlew bootRun)
# qr-service
(cd qr-service && ./gradlew bootRun)
```

### Docker Compose (MSA 전체)

```bash
cp .env.example .env   # 값 채우기 (JWT_SECRET, TOSS_SECRET_KEY, AWS_* 등)
(cd monolith && ./gradlew clean bootJar -x test)
(cd qr-service && ./gradlew clean bootJar -x test)
docker build -t $DOCKER_USERNAME/keeping-backend:latest ./monolith
docker compose -f docker-compose.msa.yml up -d   # qr-service는 compose에서 자동 빌드
```

- Nginx: `http://localhost:80`
- monolith 내부: `8080` (compose에서는 테스트용으로 외부 노출됨)
- qr-service 내부: `8082` (expose만, 외부 노출 없음)
- MySQL 외부: `3307` → 컨테이너 `3306`
- Redis 외부: `6379`

`docker-compose.yml`은 **wiremock 전용**이며 서비스 실행에는 사용하지 않는다. 외부 API(Toss 등) 모킹용.

## 환경 변수

`.env.example`을 기준으로 한 필수/선택 변수. `JWT_SECRET`은 **monolith와 qr-service가 동일한 값**이어야 한다 (qr-service가 모놀리식 발급 JWT를 그대로 검증).

| 이름 | 필수 | 설명 |
|---|---|---|
| `DOCKER_USERNAME` | ○ | Docker Hub 계정 (compose에서 monolith 이미지 태그에 사용) |
| `MYSQL_ROOT_PASSWORD` | ○ | MySQL root 비밀번호 (기본 `1234`) |
| `MYSQL_DATABASE` | ○ | 모놀리식용 DB (`.env.example` 기본 `keeping`. **단 `application-prod.yml`의 DB URL 기본값은 `ssafy_fintech_db` — 실제 배포에서는 `SPRING_DATASOURCE_URL`로 반드시 오버라이드**) |
| `JWT_SECRET` | ○ | HS256 서명키(Base64 권장, 256bit↑). 두 서비스 공유 |
| `TOSS_SECRET_KEY` | ○ | 토스페이먼츠 시크릿 키. `.env.example`은 플레이스홀더(`test_sk_xxxxxxxxxx...`), `application.yml`/`docker-compose.msa.yml`은 실제 테스트 키(`test_sk_Gv6LjeKD8aBnMEWAZA0Y3wYxAdXy`) 기본값. 배포 시 실키로 반드시 덮어쓸 것 |
| `INTERNAL_AUTH_TOKEN` | ○ | qr-service ↔ monolith `/internal/*` 호출용. `X-Internal-Auth` 헤더로 전송. **`.env.example`에는 누락되어 있음** — 로컬/배포 시 수동으로 `.env`에 추가해야 함. 미설정 시 하드코딩 기본값 `internal-service-token-12345`로 동작 |
| `AWS_ACCESS_KEY` / `AWS_SECRET_KEY` / `AWS_REGION` / `AWS_S3_BUCKET` | △ | 이미지 업로드 사용 시. **기본 리전 주의**: `application.yml`은 `ap-southeast-2`, `docker-compose.msa.yml` 환경변수 기본값은 `ap-northeast-2` — 배포 시 명시적으로 설정할 것 |
| `CLOVA_OCR_URL` / `CLOVA_OCR_SECRET` / `CLOVA_TEMPLATE_IDS` | △ | 사업자등록증 OCR 사용 시 |
| `OPENAI_API_KEY` / `OPENAI_API_URL` / `OPENAI_MODEL` | △ | 메뉴판 OCR 사용 시 |
| `FE_BASE_URL` | ○ | 프론트 URL (OAuth 콜백 리다이렉트 등) |
| `QR_SERVICE_URL` | prod | monolith → qr-service URL (docker 기본 `http://qr-service:8082`) |
| `QR_WEBHOOK_ENABLED` | prod | Store/Menu 변경 webhook 발송 on/off (기본 true) |
| `CACHE_MODE` | qr | `NONE`/`CACHE_ASIDE`/`WRITE_THROUGH` (기본 `WRITE_THROUGH`) |
| `CACHE_WARMING_ENABLED` | qr | 시작 시 Store/Menu 전량 프리로드 (기본 true) |
| `LOADTEST_BACKDOOR_ENABLED` | △ | 부하테스트용 인증 우회 |

**Kakao OAuth client-id/secret**은 현재 `application-prod.yml`과 `application-local.yml`에 **하드코딩**되어 있음. 환경변수로 분리되어 있지 않다.

## Spring 프로필

| 프로필 | 용도 | 특이점 |
|---|---|---|
| `local` | IDE 로컬 개발 | `ddl-auto=update`, `show-sql=true`, Redis/MySQL localhost |
| `prod` | Docker 배포 | `ddl-auto=validate`, OAuth redirect-uri=`{baseUrl}/login/oauth2/...`, QR webhook 활성 |
| `loadtest` | 부하테스트 | 더미 OAuth, `loadtest.backdoor.enabled=true`, 로그 WARN |
| `perf` | (qr-service 등) 성능측정 테스트 | SecurityConfigPerf + TestHeaderAuthenticationFilter 활성 |
| `docker` (qr-service) | 컨테이너 | mysql/redis 컨테이너명으로 연결 |

기본 활성 프로필은 `local`(application.yml). compose에서는 `prod,loadtest`로 덮어씌움.

## 공통 컨벤션

### 응답 포맷
- 성공/실패 모두 `global.response.ApiResponse<T>` 래핑. 필드: `success/status/message/data/timestamp`.
- 예외는 `global.exception.CustomException` + `ErrorCode` enum(HTTP 상태 + 한국어 메시지). 전역 처리는 `GlobalExceptionHandler`.
- 외부 API 호출 결과는 `ExternalApiResponse` / `ExternalApiErrorResponse` 계열.

### 에러 코드
- 모든 비즈니스 예외는 `ErrorCode` enum에만 정의. 새 에러 추가 시 이 enum만 건드릴 것.
- 멱등성 관련: `IDEMPOTENCY_KEY_REQUIRED/INVALID/BODY_CONFLICT/REPLAY_UNAVAILABLE`.
- QR 관련: `QR_NOT_FOUND/EXPIRED/STORE_MISMATCH`.
- 결제/자금 관련: `PAYMENT_INTENT_*`, `FUNDS_*`, `PAYMENT_STATUS_CONFLICT`.

### 멱등성
결제 계열 API는 `Idempotency-Key`(UUID) 필수 + canonical body SHA-256 + 응답 스냅샷 재생. **상세 규칙은 `monolith/.../domain/idempotency/CLAUDE.md` 및 `qr-service/.../domain/idempotency/CLAUDE.md` 참조.**

### 인증
JWT(HS256, issuer `kakao-oauth2-jwt`) + Refresh 싱글세션(Redis) + `X-Internal-Auth` 내부 호출. **상세는 `monolith/.../domain/auth/CLAUDE.md` 및 `qr-service/CLAUDE.md` 참조.**

### 관측
- `/actuator/health`, `/actuator/prometheus`, `/actuator/metrics` 노출.
- 로그 패턴에 `traceId`, `spanId` 포함 (Brave).
- 프로덕션은 sampling 0.1 권장이나 기본값 1.0 (application.yml).

### Transaction / 락
결제·지갑 핵심 경로는 `PESSIMISTIC_WRITE + 3초 타임아웃` / 락 타임아웃 시 `PAYMENT_IN_PROGRESS`(409). Lot 차감은 원자적 UPDATE + 비관락 혼용. **상세는 `monolith/.../domain/wallet/CLAUDE.md`, `domain/payment/CLAUDE.md` 참조.**

### 패키지 구조 (도메인별 반복 패턴)
```
domain/<name>/
├── controller/     (REST)
├── service/        (비즈니스 로직, @Transactional)
├── repository/     (Spring Data JPA)
├── model/ | entity/ (JPA Entity)
├── dto/            (요청/응답 DTO)
└── constant/ | enums/ (상수·enum)
```

## 주요 디렉토리

```
backend/
├── monolith/                            (Spring Boot app, 포트 8080 — 독립 Gradle 프로젝트)
│   ├── src/main/java/com/ssafy/keeping/
│   │   ├── KeepingApplication.java     (@SpringBootApplication + Scheduling + JpaAuditing)
│   │   ├── domain/                     (14개 도메인, 각자 CLAUDE.md)
│   │   └── global/                     (공통 config, 예외, 응답, 보안, S3, util)
│   ├── src/main/resources/
│   │   ├── application.yml + application-{local,prod,loadtest}.yml
│   │   ├── keeping.sql                 (참조용 DDL 스냅샷)
│   │   └── static/
│   ├── build.gradle, settings.gradle, Dockerfile, gradlew
│   └── .dockerignore
├── qr-service/                          (Spring Boot app, 포트 8082 — 독립 Gradle 프로젝트)
│   ├── src/main/java/com/ssafy/keeping/qr/   (acl / common / config / domain / loadtest / security)
│   └── build.gradle, settings.gradle, Dockerfile, gradlew
├── gateway/nginx.conf                   (리버스 프록시)
├── docker-compose.msa.yml               (배포 전체 — nginx, monolith, qr-service, mysql, redis)
├── docker-compose.yml                   (wiremock 전용)
├── monitoring/                          (Prometheus, Grafana)
├── wiremock/                            (외부 API 모킹)
├── k6/                                  (부하테스트 — prepayment / performance-comparison / aws-loadtest 3세트)
├── deploy/                              (배포 구성 — monolith / nginx / qr-service / redis)
├── mysql/init/                          (MySQL 초기화 SQL, compose에서 자동 로드)
└── docs/                                (설계 문서)
```

상위 `C:/keeping/`에는 `backend/`와 별도로 `frontend/` (Next.js) 가 있다. 저장소 루트는 `backend/`.

## 도메인 인덱스

각 도메인의 요약·규칙·주의사항은 해당 디렉토리의 `CLAUDE.md`에 있다. 도메인별 작업 시 `.claude/agents/`의 전용 서브에이전트가 해당 문서를 먼저 읽고 움직이도록 구성돼 있음.

**monolith** (`monolith/src/main/java/com/ssafy/keeping/domain/`)
- `auth`, `user`, `charge`, `payment`, `wallet`, `idempotency`, `group`,
  `store`, `menu`, `menucategory`, `notification`, `favorite`, `ocr`,
  `internal`
- 공통 레이어: `monolith/.../global/CLAUDE.md`

**qr-service** (`qr-service/src/main/java/com/ssafy/keeping/qr/`)
- `domain/idempotency`, `domain/intent`, `domain/qr`, `acl`
- 서비스 레벨 개요: `qr-service/CLAUDE.md`

## 주의사항 (프로젝트 전역)

1. **결제·인증 3종 헤더**: `Idempotency-Key`(UUID) / `JWT_SECRET` 공유 / `X-Internal-Auth`. 세부는 각 도메인 CLAUDE.md 참조(idempotency / auth / internal).
2. **프로필 혼동**: `prod`는 `ddl-auto=validate`. 스키마 변경 후 마이그레이션 없이 배포하면 기동 실패.
