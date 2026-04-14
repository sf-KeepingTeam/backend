<div align="center">

# Keeping

### 선결제 포인트 기반 QR 결제 플랫폼

![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.5-6DB33F?logo=springboot&logoColor=white)
![Java](https://img.shields.io/badge/Java-21%20%2F%2017-007396?logo=openjdk&logoColor=white)
![Spring Cloud](https://img.shields.io/badge/Spring_Cloud-2024.0.0-6DB33F?logo=spring&logoColor=white)
![Spring Security](https://img.shields.io/badge/Spring_Security-OAuth2_%2B_JWT-6DB33F?logo=springsecurity&logoColor=white)
![MySQL](https://img.shields.io/badge/MySQL-8.0-4479A1?logo=mysql&logoColor=white)
![Redis](https://img.shields.io/badge/Redis-7-DC382D?logo=redis&logoColor=white)
![Resilience4j](https://img.shields.io/badge/Resilience4j-2.2-E10098?logo=&logoColor=white)
![Nginx](https://img.shields.io/badge/Nginx-Gateway-009639?logo=nginx&logoColor=white)
![Docker](https://img.shields.io/badge/Docker_Compose-MSA-2496ED?logo=docker&logoColor=white)
![Toss Payments](https://img.shields.io/badge/Toss_Payments-Gateway-0064FF?logo=&logoColor=white)

</div>

---

## 목차

1. [프로젝트 소개](#1-프로젝트-소개)
2. [아키텍처](#2-아키텍처)
3. [전체 기능 목록](#3-전체-기능-목록)
4. [핵심 기능 상세](#4-핵심-기능-상세)
5. [역할 기반 접근 제어](#5-역할-기반-접근-제어)
6. [API 엔드포인트 전체 목록](#6-api-엔드포인트-전체-목록)
7. [기술적 의사결정](#7-기술적-의사결정)
8. [Quick Start](#8-quick-start)
9. [프로젝트 문서](#9-프로젝트-문서)

---

## 1. 프로젝트 소개

**Keeping**은 고객이 매장에 포인트를 미리 선결제하고, 이를 개인/모임 지갑에서 관리한 뒤 매장에서 QR로 결제하는 소상공인용 포인트 플랫폼입니다.

원래 모놀리식으로 출발한 서비스에서 결제 핵심 경로(QR 토큰 발급 + 결제 의도 승인)를 **독립 Spring Boot 서비스(qr-service)** 로 분리했고, **Nginx API Gateway**가 경로 기반으로 두 서비스에 라우팅합니다. 결제 안정성(멱등성·비관락·자동 복구)과 서비스 간 느슨한 결합(ACL·캐시 webhook·서킷 브레이커)에 집중한 MSA 구조입니다.

### 기술 스택

| 분류 | 기술 |
|------|------|
| **Framework** | Spring Boot 3.5.5, Spring Cloud 2024.0.0, Spring Security, Spring Data JPA, Spring Data Redis |
| **Language / Runtime** | Java 21 (monolith), Java 17 (qr-service) — Docker 런타임은 `eclipse-temurin:21-jre` 통일 |
| **Database** | MySQL 8.0 (논리 DB 2개: `keeping` / `payment_service`), H2 (테스트), Testcontainers |
| **Cache / Session** | Redis 7 (JWT Refresh 싱글세션, QR 토큰 TTL, PUSH 캐시) |
| **Auth** | OAuth2 Kakao + JWT HS256 (jjwt 0.12.5/0.12.3) — Access 15분 / Refresh 14일 |
| **Gateway** | Nginx (경로 기반 라우팅, `/internal/*` 외부 차단) |
| **외부 연동** | Toss Payments, Firebase Admin (FCM), AWS S3, Clova OCR, OpenAI GPT-4V, CoolSMS |
| **회복탄력성** | Resilience4j 2.2.0 (Circuit Breaker + Retry, 3종 정책), Spring Retry |
| **관측** | Micrometer + Prometheus + Brave Tracing, Zipkin reporter |
| **계약 테스트** | Spring Cloud Contract 4.1.4 (producer=monolith, consumer=qr-service) |
| **API 문서** | Springdoc OpenAPI 2.7.0 / 2.3.0 (Swagger UI) |
| **Build** | Gradle (두 서비스 각각 독립 프로젝트) |

---

## 2. 아키텍처

### 2-1. 시스템 구성도

```mermaid
graph TB
    subgraph Client["클라이언트"]
        FE["Frontend (Next.js)"]
    end

    subgraph Gateway["API Gateway"]
        NGX["Nginx :80<br/>경로 라우팅 + /internal/* 차단"]
    end

    subgraph Apps["Spring Boot 서비스"]
        MONO["monolith :8080<br/>Java 21<br/>인증/회원/매장/지갑/선결제/알림/통계"]
        QR["qr-service :8082<br/>Java 17<br/>QR 토큰/결제 의도/UNCERTAIN 복구"]
    end

    subgraph Data["데이터 계층"]
        DB[("MySQL 8.0<br/>keeping DB<br/>payment_service DB")]
        REDIS[("Redis 7<br/>Refresh 싱글세션<br/>QR 토큰 TTL<br/>PUSH 캐시")]
        S3[("AWS S3<br/>매장/메뉴/프로필 이미지")]
    end

    subgraph External["외부"]
        TOSS["Toss Payments"]
        FCM["Firebase FCM"]
        CLOVA["Naver Clova OCR"]
        GPT["OpenAI GPT-4V"]
    end

    FE -->|"HTTPS (상위 LB에서 종단)"| NGX
    NGX -->|"/api/qr, /cpqr, /payments, /api/payments/intent"| QR
    NGX -->|"그 외 전 경로"| MONO

    MONO -.->|"PUSH 캐시 Webhook<br/>Spring Retry"| QR
    QR -.->|"/internal/* + X-Internal-Auth<br/>Resilience4j CB"| MONO

    MONO --> DB
    QR --> DB
    MONO --> REDIS
    QR --> REDIS
    MONO --> S3

    MONO --> TOSS
    MONO --> FCM
    MONO --> CLOVA
    MONO --> GPT
```

### 2-2. 도메인 모듈 구조

```
backend/
├── monolith/src/main/java/com/ssafy/keeping/            Java 21, 포트 8080
│   ├── domain/
│   │   ├── auth/          OAuth2 Kakao, JWT, Refresh Rotation, PIN 인증, 회원가입 티켓
│   │   ├── charge/        선결제 예약→승인 (토스), 충전 보너스, 취소
│   │   ├── event/         결제/취소 이벤트 POJO (발행 미연동 예약 자리)
│   │   ├── favorite/      매장 찜 (소프트 딜리트)
│   │   ├── group/         모임 생성/가입/해체 + 모임장 권한 + 정산
│   │   ├── idempotency/   멱등성 키 관리 (결제·환불·포인트 공용)
│   │   ├── internal/      /internal/* — qr-service ↔ monolith 전용 API + Webhook
│   │   ├── menu/          매장 메뉴 CRUD (S3 이미지, 소프트 딜리트)
│   │   ├── menucategory/  2단계 카테고리 트리
│   │   ├── notification/  SSE + FCM + DB 3단계 전달 전략
│   │   ├── ocr/           사업자등록증(Clova) / 메뉴판(GPT-4V)
│   │   ├── payment/       결제 게이트웨이 추상화(Toss), 거래 원장, 환불
│   │   ├── store/         매장 CRUD, 상태 머신, 점주 통계
│   │   ├── user/          Customer / Owner 분리
│   │   └── wallet/        개인/모임 지갑, 매장별 잔액·Lot(FIFO), 포인트 공유/회수
│   └── global/            ApiResponse, ErrorCode, canonicalObjectMapper, S3, Firebase
│
├── qr-service/src/main/java/com/ssafy/keeping/qr/       Java 17, 포트 8082
│   ├── acl/               Store/Menu ACL + 캐시 (NONE/PULL/PUSH 3-mode)
│   ├── common/            exception, response
│   ├── config/            Security, RestTemplate(read/write/recovery), Cache, JWT
│   ├── domain/
│   │   ├── idempotency/   monolith와 동일 철학 독립 구현
│   │   ├── intent/        PaymentIntent 상태머신 + 2-phase 복구 스케줄러
│   │   └── qr/            QR 토큰/세션 (Redis @RedisHash)
│   ├── loadtest/          IndexBenchmarkController
│   └── security/          JwtAuthenticationFilter (monolith 동일 시크릿)
│
├── gateway/nginx.conf     API Gateway 라우팅
├── monitoring/            Prometheus / Grafana
├── mysql/init/            초기화 SQL (docker-compose 자동 로드)
├── k6/                    부하테스트 3세트
│   ├── prepayment/        선결제 집중 부하
│   ├── performance-comparison/  캐시 모드 비교
│   └── aws-loadtest/      원격 EC2 부하
├── deploy/                서비스별 배포 compose (monolith/nginx/qr-service/redis)
├── wiremock/              외부 API 모킹 (Toss 등)
├── docker-compose.msa.yml MSA 전체 실행
└── docs/                  아키텍처/패턴/운영/포트폴리오 문서
```

### 2-3. 서비스 간 통신 원칙

- **qr-service → monolith**: `/internal/*` 동기 호출. `X-Internal-Auth` 헤더 필수. Nginx가 외부 요청을 403으로 차단하고, 애플리케이션 레벨에서 헤더 한 번 더 검증 (이중 방어).
- **monolith → qr-service**: Store/Menu 변경 시 PUSH 캐시 Webhook (`/internal/cache/stores/*`, `/internal/cache/menus/*`). Spring Retry (500ms/1s/2s), fire-and-forget.
- **JWT 공유**: 두 서비스가 동일한 `JWT_SECRET`으로 각자 검증. Nginx는 JWT를 검증하지 않음.
- **멱등성 독립 구현**: 두 서비스 모두 동일 철학(IN_PROGRESS/DONE + body hash + 응답 스냅샷)으로 각자 구현 — DB 스키마도 분리.

---

## 3. 전체 기능 목록

### 인증 (Auth)
- OAuth2 카카오 로그인 + JWT(HS256) 발급/검증 (Access 15분 / Refresh 14일)
- Refresh Token 싱글 세션 + 로테이션 (Redis key `auth:refresh:active:{role}:{userId}`)
- 6자리 PIN 인증 (BCrypt, 5회 실패 시 5분 잠금, 낙관적 락)
- 회원가입 티켓 기반 2단계 가입 (Kakao → 10분 TTL 티켓 → 고객/점주 분기 등록)
- Nginx auth_request 대응 (`GET /auth/verify` → `X-User-Id`/`X-User-Role` 응답 헤더)
- 부하테스트용 백도어 인증 + perf 프로필 테스트 헤더 인증 우회

### 선결제 (Charge)
- 토스 결제 예약(10분 TTL) → 승인 → 포인트 적립 워크플로우
- 매장별 충전 보너스 정책 CRUD (금액대 정확 매칭, 점주 소유권 검증)
- 멱등성 기반 결제 승인 (동일 키 재요청 시 기존 거래 replay)
- 토스 결제 실패/DB 저장 실패 시 보상 트랜잭션(토스 취소 호출)
- 미사용 충전 포인트 롤백 취소 (비관락 + `WalletStoreLot` 검증)
- 만료 예약 EXPIRED 마킹 + 30일+ 정리 스케줄러
- 적립 Lot 만료기간 1년 자동 부여

### 찜 (Favorite)
- 매장 찜 토글 (소프트 딜리트 — `active` boolean + `unfavoritedAt`)
- 내 찜 목록 페이징 조회 (`favoritedAt` 내림차순)
- 특정 매장 찜 여부 단건 조회
- 점주 본인 매장의 찜 개수 조회 (소유권 강제)

### 모임 (Group)
- 모임 생성/검색/상세/수정/해체 + 12자리 UUID 초대 코드
- 가입 신청형 플로우 (PENDING/ACCEPT/REJECT, 중복 신청 차단)
- 초대 코드형 입장
- 모임장 권한 (위임, 멤버 강제 추가/내보내기, 가입 승인/거절)
- 멤버 탈퇴/내보내기 시 공유 지갑 개인 환급 (`settleShareToIndividual`)
- 모임 해체 시 전원 정산 → 잔액·Lot·멤버·지갑 순차 정리
- 모든 주요 이벤트 `afterCommit` 비동기 알림
- 검색 결과 모임장 이름 마스킹

### 멱등성 (Idempotency)
- `Idempotency-Key`(UUID) 헤더 강제 + 정규화 본문 SHA-256 해시 저장
- 복합 유니크 스코프 `(actorType, actorId, path, keyUuid)` — MERCHANT/CUSTOMER/SYSTEM 구분
- DONE 상태 응답 스냅샷 replay (200 OK), IN_PROGRESS는 202 + `Retry-After: 2s`
- `BODY_CONFLICT` 감지 (동일 키 다른 본문 → 409)
- 응답 처리 변형 4종 (`complete`, `completeStrict`, `completeWithoutSnapshot`, `completeCharge`)
- monolith와 qr-service **각자 독립 구현** (철학만 공유, 테이블 분리)

### 서비스 간 통신 (Internal)
- qr-service ↔ monolith 전용 `/internal/*` + `X-Internal-Auth` 토큰 검증 (Nginx 이중 차단)
- 자금 캡처/환불/복원 (멱등성 + 비관락 3초 타임아웃 → `PAYMENT_IN_PROGRESS`)
- 내부 PIN 설정/검증, 고객 조회
- 매장/메뉴 단건/배치/전량 조회 (캐시 워밍용 `/all` 포함)
- CUSTOMER/OWNER 타겟 알림 발송
- monolith → qr-service 캐시 webhook 발행 (Spring Retry 500ms/1s/2s, fire-and-forget)
- 결제 존재 확인 API (qr-service UNCERTAIN 복구용)

### 메뉴 / 카테고리 (Menu / MenuCategory)
- 매장 메뉴 CRUD (multipart 이미지 업로드 S3)
- 카테고리별 + 전체 메뉴 조회 (고객용 `active=true` / 점주용 분리)
- 소프트 딜리트 (`deletedAt`, 순서 계산은 삭제 포함)
- `displayOrder` 자동 부여 + 카테고리 변경 시 새 카테고리 끝으로 재배정
- 품절/비공개 토글, 중복명 검사, 일괄 삭제
- 2단계 트리 카테고리 (대분류/세분류), 자식 있으면 삭제 차단
- 변경 시 qr-service 캐시 webhook 비동기 발행

### 알림 (Notification)
- **3단계 전달 전략**: 활성 SSE → SSE push / 미접속+로그인 → FCM / 로그아웃 → DB 저장만
- SSE 구독 (TTL 60분, ConcurrentHashMap 기반 Emitter, 재연결 시 캐시 이벤트 재전송)
- FCM 토큰 등록/삭제 + 무효 토큰 자동 정리
- 20종 알림 타입 (결제·정산·그룹·시스템·DLQ·이상감지)
- Customer/Owner 분리 (URL prefix, 조회 API 분리)
- `REQUIRES_NEW` 트랜잭션으로 본 비즈니스와 독립 발송 (Customer 한정)
- Redis Refresh 키로 로그인 상태 판별
- Firebase 초기화 graceful degradation (파일 부재 시 warn만)

### OCR
- 사업자등록증 OCR (Naver Clova Template OCR) — 사업자번호/개업일자 정규화, 필드 confidence 평균
- 메뉴판 OCR (OpenAI GPT-4V, JSON Mode 강제) — 가격 0~200만원 검증, 동명 항목 최저가 dedup
- 공통 파일 검증 (jpg/jpeg/png, 최대 10MB)
- Clova `requestId`/`timestamp`/`templateIds` 자동 생성

### 결제 원장 (Payment)
- 결제 게이트웨이 추상화 (`PaymentGateway` + `PaymentGatewayFactory`) — 현재 Toss 구현
- 토스 REST 클라이언트 (confirm/cancel, Basic Auth, 5s/10s 타임아웃)
- 거래 원장(Transaction) + 품목 스냅샷(TransactionItem) — 7종 `TransactionType` (CHARGE / USE / CANCEL_CHARGE / CANCEL_USE / TRANSFER_IN / TRANSFER_OUT / REFUND)
- 자기참조 `refTransaction`으로 취소 거래 추적
- USE 거래 환불 — 멱등성 + Wallet Lot 복원 + invariant 검증 (`FUNDS_INVARIANT_VIOLATION`)
- 점주 환불 알림 발송
- `findByIdWithLock` (PESSIMISTIC 5초)

### 매장 (Store)
- 매장 CRUD + multipart 이미지 업로드
- 공개 조회(ACTIVE만) / 점주 조회 분리, 검색·카테고리 필터
- 중복 등록 방지 `(taxIdNumber, address)` 유니크
- 상태 머신: `ACTIVE → SUSPENDED(잔액 잔존) / DELETED(잔액 0)` — 비관락 잔액 확인
- 점주 통계 (누적/일별/월별/기간별, 8개 쿼리 조합)
- 모든 점주 API 권한 검증 (`storeId + ownerId` 일치)
- 변경 시 qr-service 캐시 webhook 비동기 발행

### 사용자 (User)
- Customer / Owner 완전 분리 (테이블·컨트롤러·경로 prefix)
- 고객 프로필 조회/수정 (이름·전화번호) + 이미지 업로드
- 점주 프로필 조회 + 이미지 업로드 (수정 API 없음)
- 고객 소프트 딜리트 (`@SQLDelete`) / 점주는 수동
- 내 모임 목록 조회 (Customer)
- nullable 정책 차이 (Customer 필수 / Owner 선택)

### 지갑 (Wallet)
- 개인/모임 지갑 분리 (`WalletType` INDIVIDUAL/GROUP, customer 1:1 / group 1:1)
- 매장별 잔액(`WalletStoreBalance`) + Lot(FIFO 소진, 1년 만료)
- 개인 → 모임 포인트 공유 + 모임 → 개인 회수 (멱등성 키 필수)
- 모임 해산 정산 (`settleShareToIndividual` — 매장별 기여자 환급)
- 조건부 원자 차감 (`decrementIfEnough`, `decrementLotIfEnough`)
- 비관락 PESSIMISTIC_WRITE (잔액 3초 타임아웃 → `PAYMENT_IN_PROGRESS`)
- 기여자 추적 (`contributorWallet`) — 회수 시 본인 기여분만 허용
- 불변식 강제 (Balance ≥ 0, `amountRemaining ≤ amountTotal`, `delta ≠ 0`)

### QR 결제 (qr-service 전담)
- CPQR(Customer-Presented QR) — 고객이 QR 표시, 점주가 스캔
- QR 토큰 발급/조회/취소 (TTL 10초, 스캔 즉시 삭제 → 세션 토큰 TTL 3분)
- 결제 의도(`PaymentIntent`) 상태머신: `PENDING → APPROVED / DECLINED / CANCELED / EXPIRED / UNCERTAIN → ROLLED_BACK`
- 의도 TTL 3분, 낙관락 `@Version`으로 동시 승인 충돌 방지
- PIN 검증을 monolith `/internal/customers/{id}/pin-verify`에 위임
- 자금 캡처 멱등키 결정적 생성 (`UUID.nameUUIDFromBytes("capture:" + intentPublicId)`)
- **캡처 실패(타임아웃/서킷 오픈) 시 UNCERTAIN + 10초 주기 자동 복구**
  - 2-phase: Phase 1 외부 API 호출 (트랜잭션 없음, 10초 타임아웃) / Phase 2 짧은 저장 트랜잭션
  - DB 커넥션 점유 중 외부 API 대기 금지
- Resilience4j Circuit Breaker 3종 — strict(40%) / lenient(70%) / recovery(60%)
- RestTemplate 용도별 분리 — read(3s/5s, 재시도 3) / write(2s/3s, 재시도 1 fail-fast) / recovery(5s/10s, 재시도 3)

### 캐시 (ACL · qr-service)
- Anti-Corruption Layer — Store/Menu 로컬 캐시
- **3-mode 전환 가능** (`CACHE_MODE=PUSH|PULL|NONE`)
  - `PUSH` (기본): 시작 시 전량 워밍 + monolith webhook으로 실시간 갱신
  - `PULL`: cache-aside 조회 시 적재
  - `NONE`: 항상 monolith로 호출
- `CacheWebhookController` — `X-Internal-Auth` 검증 후 Redis 갱신

### 공통 인프라 (Global)
- 표준 응답 `ApiResponse<T>` (success/status/message/data/timestamp)
- 전역 예외 처리 `GlobalExceptionHandler` + `CustomException` + `ErrorCode` enum (HTTP 상태 + 한국어 메시지)
- 외부 API 응답 래퍼 (`ExternalApiResponse`, `ExternalApiErrorMapper`)
- `canonicalObjectMapper` 빈 — 멱등성 해시용 키 정렬 직렬화
- AWS S3 이미지 업로드 (`uploadImage`, `updateProfileImage`)
- Firebase Admin 초기화 (graceful — 파일 부재 시 warn만)
- Swagger/OpenAPI, Async Executor (`webhookExecutor`)
- `Clock` 빈 (테스트 주입), `PasswordEncoder` 공용 빈
- `TxUtils` 트랜잭션 경계 유틸

---

## 4. 핵심 기능 상세

### 4-1. QR 결제 플로우 (CPQR)

```mermaid
sequenceDiagram
    participant C as Customer
    participant M as Merchant
    participant Q as qr-service
    participant MO as monolith

    C->>Q: POST /api/qr (QR 토큰 생성)
    Q-->>C: tokenId (TTL 10초, Redis)

    M->>Q: POST /api/qr/{tokenId}/scan
    Q->>Q: Redis에서 QR 삭제 (재사용 불가)
    Q-->>M: sessionToken (TTL 3분)

    M->>Q: POST /cpqr/{sessionToken}/initiate<br/>{storeId, items[]} + Idempotency-Key
    Q->>MO: /internal/menus/batch (캐시 조회 or PULL)
    Q->>Q: PaymentIntent 생성 (PENDING, TTL 3분)
    Q-->>C: 알림 (SSE/FCM) — 결제 요청
    Q-->>M: 201 Created (intentPublicId)

    C->>Q: POST /payments/{intentId}/approve<br/>{pin} + Idempotency-Key
    Q->>MO: /internal/customers/{id}/pin-verify
    Q->>MO: /internal/wallets/{w}/stores/{s}/capture<br/>(결정적 멱등키 + write 템플릿)

    alt 캡처 성공
        MO-->>Q: 자금 차감 완료
        Q->>Q: Intent APPROVED
        Q-->>C: 알림 (결제 완료)
        Q-->>M: 알림 (결제 수신)
    else 타임아웃/서킷 오픈
        Q->>Q: Intent UNCERTAIN
        Q-->>C: 202 + Retry-After
        Note over Q,MO: 10초 주기 PaymentRecoveryService<br/>Phase 1 (외부 호출, 트랜잭션 X)<br/>Phase 2 (저장 트랜잭션)
    end
```

**설계 포인트**

| 항목 | 선택 | 이유 |
|------|------|------|
| QR TTL | 10초 | 재사용 공격 최소화, 화면 표시→스캔 충분 |
| 세션 토큰 TTL | 3분 | 점주가 품목 입력할 시간 확보 |
| Intent TTL | 3분 | 고객 PIN 입력 시한 |
| 자금 캡처 멱등키 | `UUID.nameUUIDFromBytes("capture:" + intentPublicId)` 결정적 | 재시도해도 동일 키 → 이중 차감 원천 차단 |
| 낙관락 `@Version` | PaymentIntent | 동시 승인 → 충돌 시 409, 한 건만 성공 |
| UNCERTAIN 상태 | 타임아웃/서킷 오픈 | 원격 결과를 알 수 없는 상태를 명시 — 즉시 실패 처리 금지 |
| 2-phase 복구 | Phase 1 트랜잭션 분리 | DB 커넥션 점유 중 외부 API 대기 금지 |

### 4-2. 멱등성 설계

결제·환불·포인트 공유 계열 API 전반에 **같은 패턴**을 강제합니다. monolith와 qr-service에서 **독립 구현**이지만 철학·상태 전이·응답 코드는 동일.

```mermaid
stateDiagram-v2
    [*] --> IN_PROGRESS: 최초 요청 (본문 SHA-256 저장)
    IN_PROGRESS --> DONE: 비즈니스 성공 + 응답 스냅샷 저장
    IN_PROGRESS --> IN_PROGRESS: 동일 요청 재시도 → 202 + Retry-After 2s
    IN_PROGRESS --> [*]: BODY_CONFLICT (다른 본문) → 409
    DONE --> DONE: 동일 키 재요청 → 200 OK replay
    DONE --> [*]: 다른 본문 → 409
```

| 설계 포인트 | 내용 |
|------------|------|
| **복합 유니크 스코프** | `(actorType, actorId, path, keyUuid)` — MERCHANT/CUSTOMER/SYSTEM 구분해 키 네임스페이스 충돌 방지 |
| **본문 정규화** | `canonicalObjectMapper` — 키 알파벳 정렬, 배열 정렬 (items는 `menuId` 기준), whitespace 제거 후 SHA-256 |
| **응답 스냅샷** | JSON으로 DB 저장 → replay 시 원본 그대로 재생. 직렬화 실패 시 `completeWithoutSnapshot` 폴백 |
| **응답 코드 구분** | 최초 201 vs replay 200 (`okReplay`). 프론트가 201만 처리하면 재시도 시 오동작 — 문서로 명시 |
| **In-Progress 202** | 진행 중 재시도는 `Retry-After: 2` 제시 — 클라이언트가 롱폴 대신 주기 폴 유도 |
| **결정적 멱등키** | 자금 캡처는 `UUID.nameUUIDFromBytes("capture:" + intentPublicId)` — 재시도마다 동일 키 |

### 4-3. Push 기반 캐시 전략

QR 결제 경로(qr-service)의 Store/Menu 조회 지연을 잡기 위해 **PUSH 캐시**를 채택했습니다.

```mermaid
graph LR
    subgraph Mono["monolith"]
        SVC["Store/Menu Service<br/>@Transactional"]
        PUB["QrServiceWebhookPublisher<br/>@Async + Spring Retry<br/>500ms → 1s → 2s"]
    end

    subgraph QR["qr-service"]
        HOOK["CacheWebhookController<br/>X-Internal-Auth 검증"]
        WARM["CacheWarmingService<br/>@PostConstruct<br/>전량 로드"]
        CACHE["Redis Store/Menu Cache"]
    end

    SVC -->|"afterCommit<br/>Event 발행"| PUB
    PUB -->|"POST /internal/cache/..."| HOOK
    HOOK --> CACHE
    WARM -->|"시작 시 /internal/stores/all,<br/>/internal/menus/all"| CACHE
```

| 설계 포인트 | 내용 |
|------------|------|
| **3-mode 토글** | `CACHE_MODE=PUSH \| PULL \| NONE` — 런타임 전환으로 장애 시 즉시 복구 경로 |
| **PUSH 기본** | 결제 경로는 cache miss 지연을 감당할 수 없음 — cold-start 방지를 위해 시작 시 warming |
| **afterCommit 이벤트** | 트랜잭션 롤백 시 webhook 미발송 보장 |
| **fire-and-forget** | 비동기 실행자 `webhookExecutor` 사용, 실패해도 본 비즈니스 계속 |
| **Spring Retry** | 지수 백오프 (500ms/1s/2s), 최종 실패는 warn 로그만 — PULL 모드로 자연 복구 |
| **서비스 간 ACL** | qr-service는 monolith 엔티티를 직접 모르고, `StoreResponse`/`MenuResponse` DTO만 수신 |

> 상세: [docs/architecture/ADR-001-push-based-caching.md](./docs/architecture/ADR-001-push-based-caching.md)

### 4-4. 결제 안정성 (비관락 + UNCERTAIN 복구)

| 설계 포인트 | 내용 |
|------------|------|
| **PESSIMISTIC_WRITE + 3s 타임아웃** | Wallet/Balance 캡처 경로 — 락 대기 초과 시 `PAYMENT_IN_PROGRESS`(409) 반환 |
| **원자적 조건부 UPDATE** | `decrementLotIfEnough` — 한 쿼리에서 잔액 확인+차감. 동시 요청 안전 |
| **invariant 검증** | `amountRemaining ≤ amountTotal`, Balance ≥ 0 — 위반 시 `FUNDS_INVARIANT_VIOLATION` |
| **보상 트랜잭션** | 토스 결제 성공 후 DB 저장 실패 시 토스 취소 호출 |
| **UNCERTAIN 상태** | 결과를 모르는 명시적 상태 — 타임아웃/서킷 오픈 분리 |
| **복구 스케줄러** | `PaymentRecoveryService @Scheduled(10s)` — 2-phase로 DB 커넥션 절약 |

> 상세: [docs/architecture/ADR-002-payment-stability-enhancement.md](./docs/architecture/ADR-002-payment-stability-enhancement.md)

### 4-5. 알림 3단계 전달 전략

```
이벤트 발생
  → 고객/점주 타입 판별
    → Redis "auth:refresh:active:*" 로 로그인 여부 확인
      ├── SSE Emitter 활성 → SSE push (+ DB 저장)
      ├── 로그인만 되어 있음 → FCM 푸시 (+ DB 저장)
      └── 로그아웃 → DB 저장만
```

| 설계 포인트 | 내용 |
|------------|------|
| **SSE 다중 탭** | `ConcurrentHashMap<userId, Map<emitterId, SseEmitter>>` — 한 사용자 여러 연결 |
| **REQUIRES_NEW** | 알림 저장은 별도 트랜잭션 — 본 비즈니스 성공 보장 (Customer 한정) |
| **Firebase graceful** | 파일 부재 시 FCM 비활성 + warn — 개발 환경 지원 |
| **20종 타입** | 결제/정산/그룹/시스템/DLQ/이상감지 분류 |

### 4-6. Resilience4j 용도별 정책

qr-service → monolith 내부 호출은 **3가지 용도**로 나눠 각기 다른 Circuit Breaker·Retry·RestTemplate을 적용합니다.

| 용도 | CB 임계 | 타임아웃 | 재시도 | RestTemplate | 예시 |
|-----|--------|---------|-------|-------------|------|
| **strict (read-critical)** | 40% | 3s / 5s | 3회 | `readRestTemplate` | `/internal/customers`, `/internal/stores/{id}` |
| **lenient (write)** | 70% | 2s / 3s | **1회 fail-fast** | `writeRestTemplate` | `/internal/wallets/.../capture`, `/internal/wallets/.../refund` |
| **recovery** | 60% | 5s / 10s | 3회 | `recoveryRestTemplate` | `PaymentRecoveryService`의 `/internal/payments/check` |

**핵심 원칙**: 자금 캡처는 반드시 **write** 템플릿. 재시도로 이중 차감 위험 — fail-fast 후 UNCERTAIN 상태 + 복구 스케줄러로 처리.

---

## 5. 역할 기반 접근 제어

| 기능 | Customer | Owner | 비고 |
|------|:--------:|:-----:|:---:|
| 카카오 로그인 / PIN 설정 | O | O | |
| 매장/메뉴 공개 조회 | O | O | 공개 `/stores`, `/menus` |
| 선결제 (토스) | O | X | 고객 전용 |
| 충전 보너스 정책 CRUD | X | O | 점주가 정의 |
| QR 토큰 발급 / 취소 | O | X | 고객이 표시 |
| QR 스캔 / 결제 의도 생성 | X | O | 점주가 스캔·입력 |
| 결제 승인 (PIN) | O | X | |
| 결제 환불 | X | O | 점주 발행 |
| 매장 CRUD / 통계 | X | O | 본인 매장만 |
| 메뉴 CRUD | X | O | |
| 지갑 조회 | O | X | |
| 포인트 공유 / 회수 | O | X | 개인 ↔ 모임 |
| 모임 생성 / 해체 | O | X | |
| 찜 | O | X | |
| OCR (사업자등록증 / 메뉴판) | O | O | 회원가입 중 / 메뉴 등록 |
| 알림 구독 (SSE) | O | O | 경로 분리 |

Nginx 레벨 추가 제한: `/internal/*` 외부 요청 **전부 403**.

---

## 6. API 엔드포인트 전체 목록

> 인증 컬럼: **O**=JWT 필요 / **X**=공개 / **INTERNAL**=`X-Internal-Auth` 헤더 (Nginx에서 외부 차단) / **BACKDOOR**=`loadtest.backdoor.enabled=true` 전용

### Auth (`/auth`, `/oauth2`, `/signup`)

| Method | Endpoint | 설명 | 인증 |
|--------|----------|------|:----:|
| GET | `/auth/verify` | Nginx auth_request용 JWT 검증 | O |
| POST | `/auth/refresh` | Access 재발급 (Refresh 쿠키) | X |
| POST | `/auth/logout` | 로그아웃 + Refresh 폐기 | X |
| GET | `/auth/logout/kakao` | 카카오 로그아웃 리다이렉트 | X |
| POST | `/signup/customer` | 고객 회원가입 (티켓) | X |
| POST | `/signup/owner` | 점주 회원가입 (티켓) | X |
| GET | `/oauth2/authorization/{provider}` | 카카오 OAuth 시작 | X |
| GET | `/login/oauth2/code/{provider}` | OAuth 콜백 | X |
| GET | `/loadtest/health` | 부하테스트 헬스 | BACKDOOR |
| GET | `/loadtest/verify-customer` | 부하테스트 고객 인증 확인 | BACKDOOR |
| GET | `/loadtest/verify-owner` | 부하테스트 점주 인증 확인 | BACKDOOR |

### Charge

| Method | Endpoint | 설명 | 인증 |
|--------|----------|------|:----:|
| POST | `/api/v1/stores/{storeId}/prepayment/reserve` | 선결제 예약 (10분) | O |
| POST | `/api/v1/stores/{storeId}/prepayment/confirm` | 선결제 승인 (Idempotency-Key) | O |
| GET | `/api/v1/customers/cancel-list` | 취소 가능 거래 목록 | O |
| POST | `/api/v1/customers/payments/cancel` | 충전 결제 취소 | O |
| GET | `/api/v1/stores/{storeId}/charge-bonus` | 고객용 보너스 목록 | X |
| POST | `/owners/stores/{storeId}/charge-bonus` | 보너스 정책 생성 | O |
| GET | `/owners/stores/{storeId}/charge-bonus` | 보너스 정책 목록 | O |
| GET | `/owners/stores/{storeId}/charge-bonus/{chargeBonusId}` | 보너스 정책 상세 | O |
| PUT | `/owners/stores/{storeId}/charge-bonus/{chargeBonusId}` | 보너스 정책 수정 | O |
| DELETE | `/owners/stores/{storeId}/charge-bonus/{chargeBonusId}` | 보너스 정책 삭제 | O |

### Favorite

| Method | Endpoint | 설명 | 인증 |
|--------|----------|------|:----:|
| POST | `/favorites/stores/{storeId}` | 매장 찜 토글 | O |
| GET | `/favorites` | 내 찜 목록 (페이지) | O |
| GET | `/favorites/stores/{storeId}/check` | 찜 여부 확인 | O |
| GET | `/favorites/owner/stores/{storeId}/count` | 점주 매장 찜 개수 | O |

### Group

| Method | Endpoint | 설명 | 인증 |
|--------|----------|------|:----:|
| POST | `/groups` | 모임 생성 | O |
| GET | `/groups?name=...` | 모임 검색 | O |
| GET | `/groups/{groupId}` | 모임 상세 | O |
| PATCH | `/groups/{groupId}` | 모임 수정 | O |
| DELETE | `/groups/{groupId}` | 모임 해체 (리더) | O |
| GET | `/groups/{groupId}/group-members` | 멤버 목록 | O |
| POST | `/groups/{groupId}/add-requests` | 가입 신청 | O |
| GET | `/groups/{groupId}/add-requests` | 가입 신청 목록 (리더) | O |
| PATCH | `/groups/{groupId}/add-requests` | 가입 승인/거절 | O |
| POST | `/groups/{groupId}/entrance` | 초대 코드 입장 | O |
| PATCH | `/groups/{groupId}/group-leader` | 리더 위임 | O |
| POST | `/groups/{groupId}/group-member` | 멤버 강제 추가 | O |
| DELETE | `/groups/{groupId}/group-member` | 모임 탈퇴/내보내기 | O |

### Menu / MenuCategory

| Method | Endpoint | 설명 | 인증 |
|--------|----------|------|:----:|
| GET | `/stores/{storeId}/menus` | 고객용 전체 메뉴 | X |
| GET | `/stores/{storeId}/menus/categories/{categoryId}` | 카테고리별 메뉴 | X |
| GET | `/stores/{storeId}/menus/categories` | 대분류 목록 | X |
| GET | `/owners/stores/{storeId}/menus` | 점주용 메뉴 목록 | O |
| POST | `/owners/stores/{storeId}/menus` | 메뉴 생성 (multipart) | O |
| PATCH | `/owners/stores/{storeId}/menus/{menuId}` | 메뉴 수정 (multipart) | O |
| DELETE | `/owners/stores/{storeId}/menus/{menuId}` | 메뉴 삭제 | O |
| DELETE | `/owners/stores/{storeId}/menus` | 전체 메뉴 삭제 | O |
| POST | `/owners/stores/{storeId}/menus/categories` | 카테고리 등록 | O |
| GET | `/owners/stores/{storeId}/menus/categories` | 점주용 대분류 | O |
| PATCH | `/owners/stores/{storeId}/menus/categories/{categoryId}` | 카테고리 수정 | O |
| DELETE | `/owners/stores/{storeId}/menus/categories/{categoryId}` | 카테고리 삭제 | O |

### Notification (`/api/notifications`, `/api/fcm`)

| Method | Endpoint | 설명 | 인증 |
|--------|----------|------|:----:|
| GET | `/api/notifications/subscribe/customer/{customerId}` | 고객 SSE 구독 | O |
| GET | `/api/notifications/subscribe/owner/{ownerId}` | 점주 SSE 구독 | O |
| GET | `/api/notifications/customer/{customerId}` | 고객 알림 목록 | O |
| GET | `/api/notifications/owner/{ownerId}` | 점주 알림 목록 | O |
| GET | `/api/notifications/{role}/{id}/unread` | 미읽음 목록 | O |
| GET | `/api/notifications/{role}/{id}/unread-count` | 미읽음 개수 | O |
| PUT | `/api/notifications/{role}/{id}/{notificationId}/read` | 읽음 처리 | O |
| POST | `/api/fcm/{role}/{id}/token` | FCM 토큰 등록 | O |
| DELETE | `/api/fcm/token` | FCM 토큰 삭제 | O |

### OCR

| Method | Endpoint | 설명 | 인증 |
|--------|----------|------|:----:|
| POST | `/ocr/biz-license` | 사업자등록증 OCR (Clova) | O |
| POST | `/ocr/menu` | 메뉴판 OCR (GPT-4V) | O |

### Payment

| Method | Endpoint | 설명 | 인증 |
|--------|----------|------|:----:|
| POST | `/stores/{storeId}/transactions/{transactionId}/refund` | 결제 전액 환불 (Idempotency-Key) | O |

### Store

| Method | Endpoint | 설명 | 인증 |
|--------|----------|------|:----:|
| GET | `/stores` | 매장 목록/검색/카테고리 | X |
| GET | `/stores/{storeId}` | 매장 상세 | X |
| POST | `/owners/stores` | 매장 등록 (multipart) | O |
| GET | `/owners/stores` | 내 매장 목록 | O |
| GET | `/owners/stores/{storeId}` | 내 매장 상세 | O |
| PATCH | `/owners/stores/{storeId}` | 매장 수정 | O |
| DELETE | `/owners/stores/{storeId}` | 매장 삭제 (소프트) | O |
| GET | `/stores/{storeId}/statistics/overall` | 누적 통계 | O |
| POST | `/stores/{storeId}/statistics/daily` | 일별 통계 | O |
| POST | `/stores/{storeId}/statistics/period` | 기간별 통계 | O |
| POST | `/stores/{storeId}/statistics/monthly` | 월별 통계 | O |

### User

| Method | Endpoint | 설명 | 인증 |
|--------|----------|------|:----:|
| GET | `/customers/me` | 내 프로필 | O |
| PUT | `/customers/me` | 프로필 수정 | O |
| POST | `/customers/{customerId}/profile-image/upload` | 이미지 업로드 | O |
| GET | `/customers/me/groups` | 내 모임 ID 목록 | O |
| GET | `/owners/me` | 점주 프로필 | O |
| POST | `/owners/{ownerId}/profile-image/upload` | 점주 이미지 업로드 | O |

### Wallet

| Method | Endpoint | 설명 | 인증 |
|--------|----------|------|:----:|
| GET | `/wallets/individual/balance` | 개인 지갑 잔액 | O |
| GET | `/wallets/individual/stores/{storeId}/detail` | 개인 매장별 상세 | O |
| GET | `/wallets/groups/{groupId}` | 모임 지갑 조회 | O |
| GET | `/wallets/groups/{groupId}/balance` | 모임 지갑 잔액 | O |
| GET | `/wallets/groups/{groupId}/stores/{storeId}/detail` | 모임 매장별 상세 | O |
| GET | `/wallets/both/balance` | 개인+모임 동시 조회 | O |
| GET | `/wallets/{walletId}/stores/{storeId}/points/available` | 회수 가능 포인트 | O |
| POST | `/wallets/groups/{groupId}/stores/{storeId}` | 개인→모임 공유 (Idempotency-Key) | O |
| POST | `/wallets/groups/{groupId}/stores/{storeId}/reclaim` | 모임→개인 회수 (Idempotency-Key) | O |

### QR Service — QR 토큰

| Method | Endpoint | 설명 | 인증 |
|--------|----------|------|:----:|
| POST | `/api/qr` | QR 토큰 생성 (TTL 10초) | O |
| GET | `/api/qr/{tokenId}` | QR 토큰 조회 | O |
| POST | `/api/qr/{tokenId}/scan` | QR 스캔 → 세션 (점주) | O |
| DELETE | `/api/qr/{tokenId}` | QR 토큰 삭제 | O |

### QR Service — 결제 의도

| Method | Endpoint | 설명 | 인증 |
|--------|----------|------|:----:|
| POST | `/cpqr/{sessionToken}/initiate` | 결제 의도 생성 (점주, Idempotency-Key) | O |
| POST | `/payments/{intentId}/approve` | 결제 승인 (고객, Idempotency-Key) | O |
| GET | `/api/payments/intent/{intentPublicId}` | 결제 의도 상세 | O |

### Internal (서비스간 통신)

#### monolith (`X-Internal-Auth` 필수, Nginx 외부 차단)

| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/internal/customers/{customerId}` | 고객 정보 조회 |
| POST | `/internal/customers/{customerId}/pin-set` | PIN 설정 (테스트) |
| POST | `/internal/customers/{customerId}/pin-verify` | PIN 검증 |
| GET | `/internal/wallets/{walletId}/stores/{storeId}/balance` | 지갑 매장 잔액 |
| POST | `/internal/wallets/{walletId}/stores/{storeId}/capture` | 자금 캡처 (Idempotency-Key) |
| POST | `/internal/wallets/{walletId}/stores/{storeId}/restore` | 잔액 복원 |
| POST | `/internal/wallets/{walletId}/refund` | 환불 처리 (Idempotency-Key) |
| GET | `/internal/payments/check?idempotencyKey=` | 결제 존재 확인 (복구용) |
| GET | `/internal/stores/{storeId}` | 매장 단건 |
| GET | `/internal/stores/all` | 전체 활성 매장 (워밍용) |
| GET | `/internal/menus/{menuId}` | 메뉴 단건 |
| POST | `/internal/menus/batch` | 메뉴 일괄 조회 |
| GET | `/internal/menus/all` | 전체 활성 메뉴 (워밍용) |
| POST | `/internal/notifications/send` | 알림 발송 (CUSTOMER/OWNER) |

#### qr-service (`X-Internal-Auth` 필수)

| Method | Endpoint | 설명 |
|--------|----------|------|
| POST | `/internal/cache/stores/{storeId}` | Store 캐시 push |
| POST | `/internal/cache/menus/{menuId}` | Menu 캐시 push |

---

## 7. 기술적 의사결정

| 결정 | 선택 | 이유 |
|------|------|------|
| **아키텍처** | monolith + qr-service + Nginx Gateway | 결제 핵심 경로만 분리 — 전사 MSA 부담 없이 안정성·확장성 필요한 곳만 격리 |
| **서비스 간 통신** | 동기 REST + X-Internal-Auth | Kafka/이벤트 브로커 부담 회피. 결제는 동기 응답이 UX 요구라 REST가 적합 |
| **캐시 전략** | PUSH + 워밍 (3-mode 토글) | 결제 경로 cache miss 지연 회피. 장애 시 PULL/NONE로 전환 가능 |
| **멱등성 독립 구현** | monolith ↔ qr-service 각자 | DB 분리·책임 분리. 철학만 공유 (상태머신·응답 코드 동일) |
| **낙관락 vs 비관락** | PaymentIntent=`@Version` / Wallet=`PESSIMISTIC_WRITE` | 결제 의도는 짧은 트랜잭션, 지갑은 금액 차감 정합성 우선 |
| **UNCERTAIN 상태** | 캡처 타임아웃/서킷 오픈 시 별도 상태 | "결과 모름"을 명시 — 즉시 실패로 처리 시 이중 차감 위험 |
| **2-phase 복구** | 외부 호출 / 저장 트랜잭션 분리 | DB 커넥션 점유 중 외부 API 대기 금지 — 풀 고갈 방지 |
| **RestTemplate 3종** | read / write / recovery | 쓰기는 fail-fast(재시도 1회) — 이중 차감 원천 차단. 읽기·복구는 적극 재시도 |
| **Circuit Breaker 3종** | strict/lenient/recovery (40%/70%/60%) | 읽기는 엄격(빨리 열고 닫기), 쓰기는 관대(단발 실패 감내), 복구는 중간 |
| **결정적 멱등키** | `UUID.nameUUIDFromBytes("capture:" + intentId)` | 재시도마다 동일 — DB 유니크 제약으로 이중 차감 차단 |
| **JWT 공유 (서비스별 검증)** | 동일 `JWT_SECRET` + 각자 검증 | Gateway JWT 검증 부하 회피 + 각 서비스 독립 보안 |
| **응답 표준** | `ApiResponse<T>` + `ErrorCode` enum | 한국어 메시지 + HTTP 상태를 한 곳에 — 에러 일관성 |
| **본문 정규화** | `canonicalObjectMapper` (정렬 + 공백 제거) | 동일 키 "본문 약간 다름"을 SHA-256 해시 비교로 엄격 감지 |
| **외부 모킹** | WireMock (`docker-compose.yml`) | 토스 등 외부 API를 로컬/테스트에서 재현 |
| **계약 테스트** | Spring Cloud Contract 4.1.4 | producer=monolith stub → consumer=qr-service 검증. Stub JAR는 로컬 Maven (서버 불필요) |

---

## 8. Quick Start

### 사전 준비

- Java 21 (monolith), Java 17+ (qr-service 빌드 시). Docker 이미지는 모두 `eclipse-temurin:21-jre`
- Docker Desktop
- 로컬 실행 시: MySQL `localhost:3306` (user=`root` / pw=`ssafy` / db=`keeping`), Redis `localhost:6379`

### 로컬 실행 (IDE / `bootRun`)

각 서비스는 **독립 Gradle 프로젝트**입니다. 루트 Gradle 명령으로 한 번에 빌드하지 않습니다.

```bash
# monolith
(cd monolith && ./gradlew bootRun)

# qr-service (별도 터미널)
(cd qr-service && ./gradlew bootRun)
```

### Docker Compose (MSA 전체)

```bash
cp .env.example .env   # JWT_SECRET, TOSS_SECRET_KEY, AWS_* 등 채우기
(cd monolith && ./gradlew clean bootJar -x test)
(cd qr-service && ./gradlew clean bootJar -x test)
docker build -t $DOCKER_USERNAME/keeping-backend:latest ./monolith
docker compose -f docker-compose.msa.yml up -d
# qr-service 이미지는 compose의 build context로 자동 빌드
```

### 빌드 & 테스트

```bash
# 각자 디렉토리에서
(cd monolith && ./gradlew clean build)
(cd qr-service && ./gradlew clean build)

# 계약 테스트 (producer=monolith → consumer=qr-service)
(cd monolith && ./gradlew publishToMavenLocal)   # stub JAR 생성
(cd qr-service && ./gradlew test)                 # stub 사용
```

### 접속 정보

| 서비스 | URL |
|--------|-----|
| API Gateway (Nginx) | http://localhost:80 |
| monolith 직접 | http://localhost:8080 (compose에서 테스트용 노출) |
| qr-service | http://localhost:8082 (compose에서는 내부 전용) |
| monolith Swagger | http://localhost:8080/swagger-ui.html |
| qr-service Swagger | http://localhost:8082/swagger-ui.html |
| Prometheus | http://localhost:8080/actuator/prometheus |
| MySQL | localhost:3307 (컨테이너 3306) |
| Redis | localhost:6379 |

### 주요 환경 변수

| 이름 | 필수 | 설명 |
|------|:---:|------|
| `DOCKER_USERNAME` | ○ | Docker Hub 계정 (compose 이미지 태그) |
| `MYSQL_ROOT_PASSWORD` | ○ | MySQL root |
| `JWT_SECRET` | ○ | **monolith/qr-service 동일값 필수** (HS256, Base64 256bit↑ 권장) |
| `TOSS_SECRET_KEY` | ○ | 토스페이먼츠 시크릿 |
| `INTERNAL_AUTH_TOKEN` | ○ | qr-service ↔ monolith `/internal/*` 토큰 (`.env.example`에는 누락되어 있으니 추가) |
| `AWS_ACCESS_KEY` / `AWS_SECRET_KEY` / `AWS_S3_BUCKET` | △ | 이미지 업로드 |
| `CLOVA_OCR_URL` / `CLOVA_OCR_SECRET` | △ | 사업자등록증 OCR |
| `OPENAI_API_KEY` | △ | 메뉴판 OCR |
| `FE_BASE_URL` | ○ | 프론트 URL (OAuth 콜백) |
| `CACHE_MODE` | △ | `PUSH`(기본) / `PULL` / `NONE` |
| `QR_WEBHOOK_ENABLED` | △ | monolith → qr-service webhook 발송 on/off |
| `LOADTEST_BACKDOOR_ENABLED` | △ | 부하테스트용 인증 우회 |

### 인증 방식

```
Authorization: Bearer <accessToken>         # 일반 API
Idempotency-Key: <UUID>                     # 결제·환불·포인트 공유
X-Internal-Auth: <INTERNAL_AUTH_TOKEN>      # /internal/* 서비스간 호출
```

- Refresh Token은 HttpOnly Cookie (`REFRESH_TOKEN`, path=`/auth`) 자동 관리
- OCR/이미지 업로드는 `multipart/form-data`
- SSE 구독(`/api/notifications/subscribe/...`)은 `text/event-stream`

### 데이터 초기화

```bash
docker compose -f docker-compose.msa.yml down -v
```

---

## 9. 프로젝트 문서

| 분류 | 문서 | 설명 |
|------|------|------|
| **전체 가이드** | [CLAUDE.md](./CLAUDE.md) | 프로젝트 전반 구조·컨벤션·환경변수 |
| **monolith 도메인별 상세** | `monolith/src/main/java/com/ssafy/keeping/domain/<name>/CLAUDE.md` | 15개 도메인 각자의 내부 구조 |
| **공통 인프라** | [monolith/.../global/CLAUDE.md](./monolith/src/main/java/com/ssafy/keeping/global/CLAUDE.md) | ApiResponse, ErrorCode, S3, Firebase, Async Executor |
| **qr-service** | [qr-service/CLAUDE.md](./qr-service/CLAUDE.md) | 별도 서비스 전체 구조 |
| **아키텍처** | [docs/architecture/overview.md](./docs/architecture/overview.md) | 시스템 구성도, 컴포넌트, 저장소 레이아웃 |
| | [docs/architecture/service-communication.md](./docs/architecture/service-communication.md) | ACL + /internal + 인증 |
| | [docs/architecture/jwt-authentication.md](./docs/architecture/jwt-authentication.md) | JWT 검증 흐름 |
| | [docs/architecture/nginx-gateway.md](./docs/architecture/nginx-gateway.md) | Nginx 라우팅 규칙 |
| | [docs/architecture/qr-payment-flow.md](./docs/architecture/qr-payment-flow.md) | QR 결제 전체 흐름 |
| | [docs/architecture/qr-payment-sequence.md](./docs/architecture/qr-payment-sequence.md) | 시퀀스 다이어그램 |
| | [docs/architecture/ADR-001-push-based-caching.md](./docs/architecture/ADR-001-push-based-caching.md) | PUSH 캐시 전략 결정 |
| | [docs/architecture/ADR-002-payment-stability-enhancement.md](./docs/architecture/ADR-002-payment-stability-enhancement.md) | 결제 안정성 결정 |
| **구현 패턴** | [docs/patterns/acl-pattern.md](./docs/patterns/acl-pattern.md) | Anti-Corruption Layer |
| | [docs/patterns/caching.md](./docs/patterns/caching.md) | 3-mode 캐시 |
| | [docs/patterns/resilience.md](./docs/patterns/resilience.md) | Resilience4j CB·Retry 정책 |
| | [docs/patterns/concurrency-and-idempotency.md](./docs/patterns/concurrency-and-idempotency.md) | 멱등성 + 락 |
| | [docs/patterns/contract-testing.md](./docs/patterns/contract-testing.md) | Spring Cloud Contract |
| **운영** | [docs/operations/docker-compose.md](./docs/operations/docker-compose.md) | MSA compose 실행 |
| | [docs/operations/aws-load-test.md](./docs/operations/aws-load-test.md) | AWS EC2 부하테스트 |
| **포트폴리오** | [docs/portfolio/technical-review.md](./docs/portfolio/technical-review.md) | 기술 리뷰 |
| | [docs/portfolio/loadtest-results.md](./docs/portfolio/loadtest-results.md) | 부하테스트 결과 |
| **이력** | [docs/history/](./docs/history/) | 운영 노트 / 트러블슈팅 기록 |
| **아카이브** | [docs/archive/](./docs/archive/) | 완료된 마이그레이션·설계 문서 (역사 자료) |
