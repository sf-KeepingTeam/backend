# 멱등성 키 (Idempotency Key) 고도화 - TODO List

---

## 기술 선택 요약

| 항목 | 내용 |
|------|------|
| **문제** | 네트워크 타임아웃 시 Retry로 인한 중복 결제 발생 (5,000원 결제 → 10,000원 차감) |
| **해결 후보** | 재시도 금지, DB 트랜잭션 의존, 분산 트랜잭션(2PC/Saga), 멱등성 키 |
| **선택** | 멱등성 키 패턴 (HTTP 헤더 + DB 저장) |
| **이유** | 구현 간단, 성능 영향 최소, 업계 표준(Stripe, PayPal, 토스), Retry 정책과 자연스러운 통합 |

### 해결 후보 비교

| 솔루션 | 장점 | 단점 | 적합성 |
|--------|------|------|--------|
| **재시도 금지** | 구현 최단순 | 네트워크 오류 시 결제 실패 → UX 저하 | ❌ |
| **DB 트랜잭션 의존** | 강한 일관성 | 분산 환경 불가 (QR DB ≠ Monolith DB) | ❌ |
| **2PC (Two-Phase Commit)** | 강한 일관성 | 성능 저하, 구현 복잡, 가용성 저하 | ❌ |
| **Saga 패턴** | 분산 트랜잭션 가능 | 보상 트랜잭션 구현 필요, 복잡도 높음 | △ |
| **멱등성 키** | 간단, 저비용, 표준 | TTL 관리 필요, 저장소 필요 | ✅ |

### 멱등성 키 구현 전략

| 레벨 | 위치 | 방식 | 역할 |
|------|------|------|------|
| **API 레벨** | Client → QR Service | HTTP 헤더 `Idempotency-Key` | 클라이언트 재시도 중복 방지 |
| **서비스 레벨** | QR Service → Monolith | Intent 기반 결정적 키 생성 | 서비스 간 호출 중복 방지 |

### 추가 안전장치

| 기능 | 설명 |
|------|------|
| **Body Hash** | SHA-256으로 요청 본문 해시 → 동일 키 + 다른 본문 = 400 오류 |
| **IN_PROGRESS 상태** | 처리 중 동시 요청 → 202 Accepted + Retry-After |
| **응답 스냅샷** | 처리 완료 후 응답 JSON 저장 → 재시도 시 동일 응답 반환 |

> 💡 **선택 근거**: MSA 환경에서 HTTP 통신의 불확실성(타임아웃, 네트워크 오류) 해결. "응답 없음 ≠ 실패"이므로 안전한 재시도를 위해 멱등성 필수. 업계 검증된 패턴으로 신뢰성 확보

---

## 목차

- [기술 선택 요약](#기술-선택-요약)
- [목표](#목표)
- [문제 상황 (Before)](#문제-상황-before)
  - [구체적 장애 시나리오](#구체적-장애-시나리오)
  - [문제의 핵심](#문제의-핵심)
  - [장애 영향도](#장애-영향도-멱등성-없이-운영할-경우)
- [해결 방안: 멱등성 키 패턴](#해결-방안-멱등성-키-패턴)
  - [왜 멱등성 키인가?](#왜-멱등성-키인가)
  - [아키텍처](#아키텍처)
- [현재 구현 상태 (After)](#현재-구현-상태-after)
  - [구현된 컴포넌트](#구현된-컴포넌트)
  - [핵심 구현 내용](#핵심-구현-내용)
  - [데이터베이스 스키마](#데이터베이스-스키마)
- [고도화 TODO List](#고도화-todo-list)
  - [Phase 1: 만료 정책 (TTL) 구현](#phase-1-만료-정책-ttl-구현)
  - [Phase 2: 동시성 안전성 강화](#phase-2-동시성-안전성-강화)
  - [Phase 3: 모니터링 및 메트릭](#phase-3-모니터링-및-메트릭)
  - [Phase 4: 클라이언트 가이드](#phase-4-클라이언트-가이드)
- [테스트 시나리오](#테스트-시나리오)
- [k6 부하 테스트 스크립트](#k6-부하-테스트-스크립트)
- [예상 효과](#예상-효과)
- [파일 위치](#파일-위치)

---

## 목표

네트워크 재시도 시 **중복 결제 방지** 및 **안전한 분산 트랜잭션** 처리

---

## 문제 상황 (Before)

### 구체적 장애 시나리오

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                     네트워크 타임아웃으로 인한 중복 결제 장애                        │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│  [Timeline]                                                                     │
│                                                                                 │
│  09:15:00  고객 A가 결제 승인 버튼 클릭 (5,000원)                                 │
│  09:15:01  QR Service → Monolith 자금 캡처 요청 전송                              │
│  09:15:06  네트워크 불안정으로 응답 타임아웃 (5초)                                  │
│            ※ 실제로는 Monolith에서 잔액 차감 완료됨                               │
│  09:15:07  Retry 정책에 따라 동일 요청 재전송 (1차 재시도)                          │
│  09:15:08  Monolith에서 다시 잔액 차감 → 중복 결제 발생! (10,000원 차감)            │
│  09:15:09  응답 성공 수신                                                        │
│  09:15:15  고객 A 민원 접수: "5,000원인데 10,000원이 빠졌어요"                      │
│                                                                                 │
│  [문제 원인]                                                                     │
│  ┌─────────────────────────────────────────────────────────────────────────┐    │
│  │  QR Service가 응답을 받지 못해 재시도했지만,                               │    │
│  │  Monolith는 첫 번째 요청을 이미 처리 완료한 상태                           │    │
│  │  → 같은 결제가 2번 처리됨 (멱등성 보장 없음)                               │    │
│  └─────────────────────────────────────────────────────────────────────────┘    │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### 문제의 핵심

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                          중복 결제 발생 조건 분석                                  │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│  [왜 멱등성이 필요한가?]                                                         │
│                                                                                 │
│  1. MSA 분리 후 HTTP 통신 증가                                                   │
│     - 모놀리식: 메서드 호출 → 성공/실패 명확                                      │
│     - MSA: HTTP 호출 → 타임아웃, 네트워크 오류 가능                               │
│                                                                                 │
│  2. 분산 환경의 불확실성                                                          │
│     - 요청은 도착했지만 응답이 유실될 수 있음                                      │
│     - "응답 없음 = 실패"가 아님 (이미 처리됐을 수 있음)                            │
│                                                                                 │
│  3. Retry 정책의 부작용                                                          │
│     - Circuit Breaker + Retry = 복원력 향상                                      │
│     - 하지만 쓰기 작업의 경우 부수효과 중복 발생                                   │
│                                                                                 │
│  [결제 API의 특수성]                                                             │
│                                                                                 │
│  - 읽기(GET): 재시도해도 문제없음 (부수효과 없음)                                  │
│  - 쓰기(POST): 재시도 시 중복 효과 발생 가능                                       │
│    ├─ 잔액 차감: N번 호출 → N번 차감                                             │
│    ├─ 거래 내역 생성: N번 호출 → N개 생성                                         │
│    └─ 결제 알림: N번 호출 → N번 발송                                              │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### 장애 영향도 (멱등성 없이 운영할 경우)

| 영향 항목 | 수치 |
|---------|------|
| 중복 결제 발생 확률 | 네트워크 불안정 시 0.1~1% |
| 평균 환불 처리 시간 | 3~7 영업일 |
| 고객 민원 증가율 | 예상 5배 |
| CS 비용 증가 | 건당 약 15,000원 |
| 브랜드 신뢰도 하락 | 측정 불가 (치명적) |

---

## 해결 방안: 멱등성 키 패턴

### 왜 멱등성 키인가?

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                          대안 솔루션 비교                                         │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│  [대안 1: 재시도 금지]                                                           │
│  ❌ 네트워크 오류 시 결제 실패 → 사용자 경험 저하                                  │
│                                                                                 │
│  [대안 2: DB 트랜잭션 의존]                                                       │
│  ❌ 분산 환경에서 단일 DB 트랜잭션 불가능                                          │
│  ❌ QR Service DB ≠ Monolith DB (데이터 격리)                                     │
│                                                                                 │
│  [대안 3: 분산 트랜잭션 (2PC/Saga)]                                               │
│  ❌ 구현 복잡도 매우 높음                                                         │
│  ❌ 성능 오버헤드 큼                                                              │
│                                                                                 │
│  [선택: 멱등성 키 패턴] ✅                                                        │
│  ✅ 구현 간단 (HTTP 헤더 + DB 테이블)                                             │
│  ✅ 성능 영향 최소 (SELECT 1회 추가)                                              │
│  ✅ 업계 표준 (Stripe, PayPal, 토스 등 사용)                                      │
│  ✅ Retry 정책과 자연스럽게 통합                                                  │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### 아키텍처

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                          멱등성 키 처리 플로우                                     │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│  [첫 번째 요청]                                                                  │
│                                                                                 │
│  Client ──────────────────────────────────────────────► QR Service              │
│          Idempotency-Key: abc-123                           │                   │
│          Body: {pin: "123456"}                              │                   │
│                                                             ▼                   │
│                                                      ┌──────────────┐           │
│                                                      │ DB 조회      │           │
│                                                      │ abc-123 없음 │           │
│                                                      └──────────────┘           │
│                                                             │                   │
│                                                             ▼                   │
│                                                      ┌──────────────┐           │
│                                                      │ DB 저장      │           │
│                                                      │ abc-123      │           │
│                                                      │ IN_PROGRESS  │           │
│                                                      └──────────────┘           │
│                                                             │                   │
│                                                             ▼                   │
│                                                      ┌──────────────┐           │
│                                                      │ 비즈니스     │           │
│                                                      │ 로직 실행    │           │
│                                                      │ (결제 처리)  │           │
│                                                      └──────────────┘           │
│                                                             │                   │
│                                                             ▼                   │
│                                                      ┌──────────────┐           │
│  Client ◄──────────────────────────────────────────  │ DB 업데이트  │           │
│          200 OK                                      │ abc-123      │           │
│          {status: "approved"}                        │ DONE         │           │
│                                                      │ + 응답 저장  │           │
│                                                      └──────────────┘           │
│                                                                                 │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│  [재시도 요청 - 동일 Idempotency-Key]                                             │
│                                                                                 │
│  Client ──────────────────────────────────────────────► QR Service              │
│          Idempotency-Key: abc-123  (동일!)                  │                   │
│          Body: {pin: "123456"}                              │                   │
│                                                             ▼                   │
│                                                      ┌──────────────┐           │
│                                                      │ DB 조회      │           │
│                                                      │ abc-123 있음 │           │
│                                                      │ DONE 상태    │           │
│                                                      └──────────────┘           │
│                                                             │                   │
│                                                             ▼                   │
│  Client ◄──────────────────────────────────────────  ┌──────────────┐           │
│          200 OK (Replay)                             │ 저장된 응답  │           │
│          {status: "approved"}                        │ 즉시 반환    │           │
│          "이전에 처리된 요청의 결과입니다"             │ (재처리 X)   │           │
│                                                      └──────────────┘           │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 현재 구현 상태 (After)

### 구현된 컴포넌트

| 파일 | 역할 | 상태 |
|------|------|------|
| `IdempotencyKey.java` | 멱등키 엔티티 (DB 저장) | ✅ 완료 |
| `IdempotencyService.java` | 멱등키 선점/완료 처리 | ✅ 완료 |
| `IdempotencyKeyRepository.java` | DB 조회/저장 | ✅ 완료 |
| `PaymentIntentController.java` | initiate API 멱등성 | ✅ 완료 |
| `PaymentApprovalController.java` | approve API 멱등성 | ✅ 완료 |
| `FundsService.java` | 모놀리스 호출 멱등성 | ✅ 완료 |
| `WalletClient.java` | Idempotency-Key 헤더 전달 | ✅ 완료 |

### 핵심 구현 내용

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                          두 레벨의 멱등성 보장                                     │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│  [Level 1: API 레벨 멱등성]                                                      │
│                                                                                 │
│  Client → QR Service                                                            │
│  ├─ Idempotency-Key 헤더로 요청 식별                                             │
│  ├─ SHA-256으로 요청 본문 해시 (Body Hash)                                        │
│  ├─ 동일 키 + 다른 본문 → 400 오류 (IDEMPOTENCY_BODY_CONFLICT)                   │
│  └─ 응답 JSON 스냅샷 저장 → 재시도 시 동일 응답 반환                               │
│                                                                                 │
│  [Level 2: 서비스 레벨 멱등성]                                                    │
│                                                                                 │
│  QR Service → Monolith                                                          │
│  ├─ PaymentIntent.publicId 기반 결정적 키 생성                                    │
│  │   generateIdempotencyKey(intent) → UUID.nameUUIDFromBytes("capture:" + id)   │
│  ├─ 동일 Intent에 대한 재시도 → 동일 멱등키 생성                                   │
│  └─ Monolith에서도 멱등성 처리 (Idempotency-Key 헤더 수신)                         │
│                                                                                 │
│  [추가 안전장치]                                                                 │
│                                                                                 │
│  1. IN_PROGRESS 상태 관리                                                        │
│     ├─ 동시 요청 시 두 번째 요청 → 202 Accepted + Retry-After 반환                │
│     └─ "이미 처리 중입니다. 2초 후 재시도하세요"                                   │
│                                                                                 │
│  2. 요청 본문 정규화 (Canonicalization)                                           │
│     ├─ JSON 필드 순서 정렬 → 동일 내용이면 동일 해시                               │
│     └─ canonicalObjectMapper 사용 (PropertyFilter 적용)                          │
│                                                                                 │
│  3. 응답 스냅샷 저장                                                              │
│     ├─ responseJson 컬럼에 JSON 저장                                             │
│     └─ DB에서 조회 불가 시 intentPublicId로 재조회 (fallback)                      │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### 데이터베이스 스키마

```sql
CREATE TABLE idempotency_keys (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    key_uuid BINARY(16) NOT NULL,           -- 클라이언트 제공 UUID
    actor_type VARCHAR(16) NOT NULL,        -- MERCHANT / CUSTOMER / SYSTEM
    actor_id BIGINT NOT NULL,               -- 요청자 ID
    method VARCHAR(10) NOT NULL,            -- POST
    path VARCHAR(255) NOT NULL,             -- /payments/{id}/approve
    body_hash VARBINARY(32) NOT NULL,       -- SHA-256 해시
    status VARCHAR(16) NOT NULL,            -- IN_PROGRESS / DONE
    http_status INT,                        -- 200, 201 등
    response_json JSON,                     -- 응답 스냅샷
    intent_public_id BINARY(16),            -- 연관 PaymentIntent
    created_at DATETIME(3) NOT NULL,

    UNIQUE KEY uk_idem_scope (actor_type, actor_id, path, key_uuid),
    INDEX idx_idem_created (created_at)
);
```

---

## 고도화 TODO List

### Phase 1: 만료 정책 (TTL) 구현

- [ ] **1.1 멱등키 만료 시간 설정**
  - 파일: `IdempotencyKey.java`
  - 내용: `expiresAt` 필드 추가 (기본값: 24시간)
  - 이유: 영구 저장 시 DB 용량 증가

- [ ] **1.2 만료 키 조회 제외**
  - 파일: `IdempotencyKeyRepository.java`
  - 쿼리: `WHERE expiresAt > NOW()`

- [ ] **1.3 배치 정리 스케줄러**
  - 파일: `IdempotencyCleanupScheduler.java` (신규)
  - 스케줄: 매일 새벽 3시 실행
  - 내용: 만료된 키 삭제 (DONE + 24시간 경과)

---

### Phase 2: 동시성 안전성 강화

- [ ] **2.1 Pessimistic Lock 적용**
  - 파일: `IdempotencyKeyRepository.java`
  - 문제: 동시 요청 시 같은 키로 2개 row 생성 가능
  - 해결:
  ```java
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT i FROM IdempotencyKey i WHERE ...")
  Optional<IdempotencyKey> findForUpdate(...);
  ```

- [ ] **2.2 DB Unique Constraint 검증**
  - 확인: `uk_idem_scope` 제약조건 동작 확인
  - 처리: `DataIntegrityViolationException` 처리 로직

- [ ] **2.3 IN_PROGRESS 타임아웃**
  - 문제: 처리 중 서버 다운 시 영구 IN_PROGRESS 상태
  - 해결: 30초 이상 IN_PROGRESS → 자동 해제 (stale lock)
  - 파일: `IdempotencyService.java`

---

### Phase 3: 모니터링 및 메트릭

- [ ] **3.1 멱등키 히트율 메트릭**
  - 파일: `IdempotencyService.java`
  - 메트릭:
    - `idempotency_key_new_count` - 새 키 생성 수
    - `idempotency_key_replay_count` - 재사용 (캐시 히트) 수
    - `idempotency_key_conflict_count` - 본문 충돌 수

- [ ] **3.2 Grafana 대시보드**
  - 패널: 멱등키 히트율, 충돌 발생 추이
  - 알림: 충돌 발생 시 Slack 알림

- [ ] **3.3 로그 강화**
  - 로그 레벨: INFO → 멱등키 재사용 시
  - 로그 레벨: WARN → 본문 충돌 시
  - 로그 포맷: `[IDEMPOTENCY] action=REPLAY, key={}, actorId={}`

---

### Phase 4: 클라이언트 가이드

- [ ] **4.1 API 문서 업데이트**
  - Idempotency-Key 헤더 필수 여부
  - UUID v4 포맷 권장
  - 재시도 시 동일 키 사용 안내

- [ ] **4.2 SDK/예제 코드 제공**
  - JavaScript/TypeScript 예제
  - Android/iOS 예제

---

## 테스트 시나리오

### 시나리오 1: 기본 멱등성 검증

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                          테스트: 동일 요청 재전송                                  │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│  [준비]                                                                          │
│  - QR 토큰 생성 완료                                                              │
│  - PaymentIntent 생성 완료 (PENDING 상태)                                         │
│                                                                                 │
│  [테스트 단계]                                                                   │
│                                                                                 │
│  Step 1: 첫 번째 결제 승인 요청                                                   │
│  ┌────────────────────────────────────────────────┐                              │
│  │ POST /payments/{intentId}/approve              │                              │
│  │ Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000                        │
│  │ Body: {"pin": "123456"}                        │                              │
│  └────────────────────────────────────────────────┘                              │
│  예상 응답: 200 OK, {"status": "approved"}                                        │
│  예상 잔액: 10,000원 → 5,000원 (5,000원 차감)                                     │
│                                                                                 │
│  Step 2: 동일 요청 재전송 (재시도 시뮬레이션)                                      │
│  ┌────────────────────────────────────────────────┐                              │
│  │ POST /payments/{intentId}/approve              │                              │
│  │ Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000  (동일!)               │
│  │ Body: {"pin": "123456"}                        │                              │
│  └────────────────────────────────────────────────┘                              │
│  예상 응답: 200 OK, {"status": "approved"}                                        │
│  예상 메시지: "이전에 처리된 요청의 결과입니다."                                    │
│  예상 잔액: 5,000원 (변동 없음!) ✅                                               │
│                                                                                 │
│  [검증]                                                                          │
│  - 잔액이 2번 차감되지 않았는지 확인                                               │
│  - 거래 내역이 1건만 생성되었는지 확인                                             │
│  - 응답 JSON이 동일한지 확인                                                      │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### 시나리오 2: 본문 충돌 검증

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                          테스트: 동일 키 + 다른 본문                               │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│  Step 1: 첫 번째 요청                                                            │
│  ┌────────────────────────────────────────────────┐                              │
│  │ Idempotency-Key: abc-123                       │                              │
│  │ Body: {"pin": "123456"}                        │                              │
│  └────────────────────────────────────────────────┘                              │
│  예상: 200 OK                                                                    │
│                                                                                 │
│  Step 2: 동일 키 + 다른 본문                                                      │
│  ┌────────────────────────────────────────────────┐                              │
│  │ Idempotency-Key: abc-123  (동일!)              │                              │
│  │ Body: {"pin": "654321"}   (다름!)              │                              │
│  └────────────────────────────────────────────────┘                              │
│  예상: 400 Bad Request                                                           │
│  예상 에러: IDEMPOTENCY_BODY_CONFLICT                                             │
│  예상 메시지: "동일한 멱등키로 다른 요청을 보낼 수 없습니다."                        │
│                                                                                 │
│  [검증]                                                                          │
│  - 악의적인 키 재사용 시도 차단                                                    │
│  - 클라이언트 버그 조기 발견                                                       │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### 시나리오 3: 동시 요청 처리

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                          테스트: 동시에 같은 요청 2개                              │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│  [시나리오]                                                                       │
│  - 클라이언트가 네트워크 지연으로 동일 요청을 빠르게 2번 전송                        │
│  - 두 요청이 거의 동시에 서버에 도착                                               │
│                                                                                 │
│  ┌──────────────────┐      ┌──────────────────┐                                 │
│  │  Request A       │      │  Request B       │                                 │
│  │  09:15:00.000    │      │  09:15:00.010    │                                 │
│  │  Key: abc-123    │      │  Key: abc-123    │                                 │
│  └────────┬─────────┘      └────────┬─────────┘                                 │
│           │                         │                                           │
│           ▼                         ▼                                           │
│  ┌─────────────────────────────────────────────────┐                            │
│  │              QR Service                         │                            │
│  │  ┌─────────────────────────────────────────┐    │                            │
│  │  │  Request A: beginOrLoad → 새 키 생성    │    │                            │
│  │  │  → IN_PROGRESS 상태로 저장              │    │                            │
│  │  │  → 비즈니스 로직 실행 중...             │    │                            │
│  │  └─────────────────────────────────────────┘    │                            │
│  │  ┌─────────────────────────────────────────┐    │                            │
│  │  │  Request B: beginOrLoad → 기존 키 발견  │    │                            │
│  │  │  → IN_PROGRESS 상태 확인                │    │                            │
│  │  │  → 202 Accepted + Retry-After: 2        │    │                            │
│  │  └─────────────────────────────────────────┘    │                            │
│  └─────────────────────────────────────────────────┘                            │
│                                                                                 │
│  [예상 결과]                                                                     │
│  - Request A: 200 OK (정상 처리)                                                 │
│  - Request B: 202 Accepted (재시도 안내)                                          │
│                                                                                 │
│  [검증]                                                                          │
│  - 두 요청 중 하나만 실제 처리됨                                                   │
│  - Race Condition으로 인한 중복 처리 없음                                          │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### 시나리오 4: 네트워크 재시도 통합 테스트

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                          테스트: Retry + 멱등성 통합                               │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│  [시나리오]                                                                       │
│  - 첫 번째 요청: 모놀리스 응답 타임아웃 (처리는 완료됨)                             │
│  - Retry 발동: 동일 키로 재시도                                                   │
│  - 결과: 중복 처리 없이 캐시된 응답 반환                                           │
│                                                                                 │
│  [테스트 코드 예시]                                                               │
│                                                                                 │
│  @Test                                                                          │
│  void 네트워크_재시도_시_중복_결제_방지() {                                        │
│      // Given: 결제 대기 상태                                                     │
│      UUID intentId = createPendingIntent(5000L);                                 │
│      String idempotencyKey = UUID.randomUUID().toString();                       │
│      int initialBalance = getBalance(walletId);                                  │
│                                                                                 │
│      // When: 첫 번째 요청 (성공)                                                 │
│      approve(intentId, idempotencyKey, "123456");                                │
│                                                                                 │
│      // When: 재시도 요청 (동일 키)                                               │
│      var response = approve(intentId, idempotencyKey, "123456");                 │
│                                                                                 │
│      // Then: 응답은 정상, 잔액은 1번만 차감                                       │
│      assertThat(response.isReplay()).isTrue();                                   │
│      assertThat(getBalance(walletId)).isEqualTo(initialBalance - 5000);          │
│      assertThat(countTransactions(walletId)).isEqualTo(1);                       │
│  }                                                                              │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## k6 부하 테스트 스크립트

### 멱등성 부하 테스트

```javascript
// monitoring/load-tests/scenarios/idempotency/idempotency-test.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

export let options = {
  scenarios: {
    // 시나리오 1: 정상 요청 (새 키)
    normal_requests: {
      executor: 'constant-arrival-rate',
      rate: 100,
      timeUnit: '1s',
      duration: '2m',
      preAllocatedVUs: 100,
      exec: 'normalRequest',
    },
    // 시나리오 2: 재시도 시뮬레이션 (동일 키 재전송)
    retry_simulation: {
      executor: 'constant-arrival-rate',
      rate: 20,
      timeUnit: '1s',
      duration: '2m',
      preAllocatedVUs: 50,
      exec: 'retryRequest',
      startTime: '30s',
    },
  },
  thresholds: {
    'http_req_duration{scenario:normal_requests}': ['p(95)<500'],
    'http_req_duration{scenario:retry_simulation}': ['p(95)<100'], // 캐시 히트는 빨라야 함
    'idempotency_replay_rate': ['value>0.9'], // 재시도의 90%는 캐시 히트
  },
};

export function normalRequest() {
  const idempotencyKey = uuidv4();
  const res = http.post(
    `${__ENV.BASE_URL}/payments/${__ENV.INTENT_ID}/approve`,
    JSON.stringify({ pin: '123456' }),
    {
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': idempotencyKey,
      },
    }
  );

  check(res, {
    'status is 200': (r) => r.status === 200,
  });
}

const sharedKeys = {};

export function retryRequest() {
  // VU별로 동일한 키 재사용
  const vuKey = `vu-${__VU}`;
  if (!sharedKeys[vuKey]) {
    sharedKeys[vuKey] = uuidv4();
  }

  const res = http.post(
    `${__ENV.BASE_URL}/payments/${__ENV.INTENT_ID}/approve`,
    JSON.stringify({ pin: '123456' }),
    {
      headers: {
        'Content-Type': 'application/json',
        'Idempotency-Key': sharedKeys[vuKey],
      },
    }
  );

  const isReplay = res.json('message')?.includes('이전에 처리된');

  check(res, {
    'status is 200': (r) => r.status === 200,
    'is replay response': () => isReplay,
  });
}
```

---

## 예상 효과

| 항목 | Before | After | 개선율 |
|------|--------|-------|--------|
| 중복 결제 발생률 | 0.1~1% | 0% | **100% 방지** |
| 재시도 응답 시간 | ~500ms (재처리) | ~10ms (캐시) | **98% 감소** |
| 환불 민원 건수 | 예상 N건/월 | 0건/월 | **100% 감소** |
| 데이터 정합성 | 불안정 | 보장됨 | ✅ |

---

## 파일 위치

| 파일 | 설명 |
|------|------|
| `services/qr-service/src/main/java/.../idempotency/model/IdempotencyKey.java` | 멱등키 엔티티 |
| `services/qr-service/src/main/java/.../idempotency/service/IdempotencyService.java` | 멱등키 처리 서비스 |
| `services/qr-service/src/main/java/.../idempotency/repository/IdempotencyKeyRepository.java` | DB 레포지토리 |
| `services/qr-service/src/main/java/.../intent/controller/PaymentApprovalController.java` | 결제 승인 API |
| `services/qr-service/src/main/java/.../intent/service/PaymentIntentService.java` | 결제 비즈니스 로직 |
| `services/qr-service/src/main/java/.../intent/service/FundsService.java` | 자금 캡처 (모놀리스 호출) |
| `services/qr-service/src/main/java/.../acl/WalletClient.java` | 모놀리스 Wallet API 클라이언트 |

---

*마지막 업데이트: 2025-02-26*
