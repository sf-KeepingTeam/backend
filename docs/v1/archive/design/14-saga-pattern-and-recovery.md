# Saga 패턴과 장애 복구

**작성일**: 2026-02-05
**목적**: Saga 패턴 구현 방식과 서버 장애 시 복구 전략 정리

---

## 1. Saga 패턴은 메시지 브로커가 필수인가?

### Q: Saga 패턴은 Kafka/RabbitMQ가 필수인가?

**아니요, 필수는 아닙니다!** 두 가지 방식이 있습니다.

#### 1. Choreography (이벤트 기반) - 메시지 브로커 필요

```
서비스들이 이벤트로 서로 반응

[Payment]──발행──►[Kafka]──구독──►[Wallet]──발행──►[Kafka]──구독──►[Notification]

- 각 서비스가 독립적으로 이벤트 발행/구독
- Kafka, RabbitMQ 등 필요
```

#### 2. Orchestration (오케스트레이터 기반) - HTTP만으로 가능!

```
중앙 조율자가 순서대로 호출

[Orchestrator]
      │
      ├──HTTP──► [Payment] 예약
      │              │
      │◄─────────────┘ 성공
      │
      ├──HTTP──► [Wallet] 차감
      │              │
      │◄─────────────┘ 성공
      │
      └──HTTP──► [Payment] 확정
```

#### 비교

| 방식 | 메시지 브로커 | 복잡도 | 장점 |
|------|-------------|--------|------|
| Choreography | **필요** (Kafka 등) | 높음 | 느슨한 결합 |
| **Orchestration** | **불필요** (HTTP 가능) | 낮음 | 흐름 파악 쉬움 |

**우리 프로젝트**: Payment Service가 Orchestrator 역할, HTTP 동기 호출로 Saga 구현 가능

---

### Q: HTTP Saga에서 서버가 결제 도중 죽으면?

**문제 상황:**
```
[Payment Service]
      │
      ├──HTTP──► [Wallet] 1000원 차감 ✅ 완료
      │
      ╳ 서버 다운! (여기서 죽음)
      │
      └──(미실행)──► [Payment] 확정

결과: 돈은 빠졌는데, 결제는 미완료 😱
```

**HTTP vs 메시지 브로커 차이:**

| 상황 | HTTP Saga | Kafka Saga |
|------|-----------|------------|
| 서버 다운 | **메모리 상태 손실** | 메시지 디스크에 보관 |
| 재시작 후 | 어디까지 했는지 모름 | 메시지 다시 소비 가능 |

---

### 해결책: Saga Log (DB에 상태 저장)

```java
// 1. 각 단계를 DB에 기록
@Entity
public class SagaLog {
    Long paymentId;
    String step;        // "WALLET_RESERVED", "WALLET_CAPTURED", "COMPLETED"
    String status;      // "PENDING", "SUCCESS", "FAILED"
    LocalDateTime createdAt;
}

// 2. 결제 흐름
public void approve(Long paymentId) {
    // Step 1: 예약 시작 기록
    sagaLogRepository.save(new SagaLog(paymentId, "WALLET_RESERVE", "PENDING"));

    // Step 2: Wallet 호출
    walletClient.reserve(walletId, amount);

    // Step 3: 성공 기록
    sagaLogRepository.save(new SagaLog(paymentId, "WALLET_RESERVE", "SUCCESS"));

    // ... 다음 단계
}
```

**서버 재시작 후 복구:**

```java
@Component
public class SagaRecoveryJob {

    @Scheduled(fixedDelay = 60000)  // 1분마다 실행
    public void recoverIncompleteSagas() {
        // 1. 미완료 Saga 찾기 (5분 이상 지난 것만!)
        List<SagaLog> incomplete = sagaLogRepository
            .findByStatusAndCreatedAtBefore("PENDING", 5분전);
        //                                             ^^^^^^
        //                                    5분 이상 지난 것만!

        // 2. 각각 복구 또는 롤백
        for (SagaLog log : incomplete) {
            if (log.getStep().equals("WALLET_RESERVE")) {
                // Wallet 예약만 됐으면 → 롤백
                walletClient.cancel(log.getPaymentId());
            }
        }
    }
}
```

---

### Q: Recovery Job이 정상 결제를 롤백하면 어떡해?

**걱정되는 시나리오:**
```
00:00  유저 결제 시작 → PENDING 저장
00:01  Recovery Job 실행 → PENDING 발견 → 롤백?!
00:02  결제 완료 예정이었는데... 💥
```

**해결: "5분 전" 시간 조건!**

```java
.findByStatusAndCreatedAtBefore("PENDING", 5분전);
//                                         ^^^^^^
// 5분 이상 지난 PENDING만 복구 대상!
```

**실제 동작:**
```
타임라인:

00:00  유저 결제 시작 → PENDING 저장
00:01  Recovery Job 실행 → "5분 전" 조건 → 방금 건 무시 ✅
00:02  결제 완료 → SUCCESS로 변경
00:03  (정상 종료)

05:00  Recovery Job 실행 → 이미 SUCCESS라 무시 ✅
```

**만약 서버가 다운됐다면:**
```
00:00  유저 결제 시작 → PENDING 저장
00:01  서버 다운! 💥
       ...
       (서버 재시작)
       ...
05:01  Recovery Job 실행
       → "5분 전 PENDING" 발견!
       → 롤백 실행 ✅ (이건 정상)
```

**시간 설정 기준:**

| 시간 | 장점 | 단점 |
|------|------|------|
| 1분 | 빠른 복구 | 정상 결제 롤백 위험 |
| **5분** | 안전한 여유 | 복구 느림 |
| 30분 | 매우 안전 | 복구 매우 느림 |

**추가 안전장치: DB 락(Lock)**

```java
@Transactional
public void approve(Long paymentId) {
    // 1. 해당 결제 건에 락 획득
    PaymentIntent payment = paymentRepository
        .findByIdWithLock(paymentId);  // SELECT ... FOR UPDATE

    // 2. 이미 처리 중이면 스킵
    if (payment.getStatus() != PENDING) {
        return;  // 이미 다른 스레드가 처리 중
    }

    // 3. 처리 시작
    payment.setStatus(PROCESSING);
    // ...
}
```

Recovery Job도 같은 락을 사용 → **동시에 같은 건 처리 방지!**

---

### 방식 비교 정리

| 방식 | 상태 저장 | 복구 방법 | 복잡도 |
|------|----------|----------|--------|
| HTTP + 메모리 | ❌ 없음 | 불가능 | 낮음 |
| **HTTP + DB (Saga Log)** | ✅ DB | Recovery Job | **중간** |
| Kafka | ✅ 디스크 | 자동 재소비 | 높음 |

**결론:**
- 단순 결제 흐름 → **HTTP + Saga Log**로 충분
- Kafka의 장점 = 자동 복구, 하지만 직접 구현해도 가능
- Race Condition 방지 = 시간 조건 + DB 락

---

## 2. Saga Log 테이블 설계 (Transactional Outbox 패턴)

Saga Log는 아키텍처 용어로 **Transactional Outbox Pattern**이라고 부릅니다.
DB를 메시지 큐처럼 사용하는 패턴입니다.

### 테이블 스키마

```sql
CREATE TABLE saga_log (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    aggregate_id    VARCHAR(255) NOT NULL,      -- paymentId (비즈니스 키)
    event_type      VARCHAR(50) NOT NULL,       -- WALLET_CAPTURE, SEND_NOTIFICATION
    target_service  VARCHAR(50) NOT NULL,       -- WALLET, NOTIFICATION
    status          VARCHAR(20) NOT NULL,       -- PENDING, PROCESSING, SUCCESS, FAILED
    payload         JSON,                        -- {"walletId": 123, "amount": 1000}
    retry_count     INT DEFAULT 0,
    max_retries     INT DEFAULT 3,
    next_retry_at   DATETIME,
    error_message   TEXT,
    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_status_retry (status, next_retry_at),  -- 스케줄러 조회용
    INDEX idx_aggregate (aggregate_id)                -- 비즈니스 키 조회용
);
```

### 컬럼 설명

| 컬럼명 | 타입 | 설명 |
|--------|------|------|
| id | Long | 로그 고유 식별자 |
| aggregate_id | String | 비즈니스 키 (paymentId, orderId) |
| event_type | String | 이벤트 종류 (WALLET_CAPTURE, NOTIFICATION) |
| target_service | String | 대상 서비스 (WALLET, NOTIFICATION) |
| status | Enum | PENDING, PROCESSING, SUCCESS, FAILED |
| payload | JSON | 외부 서비스에 보낼 데이터 |
| retry_count | Integer | 재시도 횟수 |
| max_retries | Integer | 최대 재시도 횟수 (기본 3회) |
| next_retry_at | DateTime | 다음 재시도 예정 시간 |
| error_message | String | 실패 원인 (디버깅용) |
| created_at | DateTime | 생성 시간 |
| updated_at | DateTime | 수정 시간 |

---

## 3. 스케줄러 구현 (지수 백오프)

### Outbox Processor

```java
@Component
@RequiredArgsConstructor
public class OutboxProcessor {

    private final SagaLogRepository sagaLogRepository;
    private final WalletClient walletClient;
    private final NotificationClient notificationClient;

    @Scheduled(fixedDelay = 30000)  // 30초마다 실행
    @Transactional
    public void processOutbox() {
        List<SagaLog> pending = sagaLogRepository.findByStatusAndNextRetryAtBefore(
            "PENDING",
            LocalDateTime.now()
        );

        for (SagaLog log : pending) {
            processLog(log);
        }
    }

    private void processLog(SagaLog log) {
        try {
            // 1. PROCESSING으로 변경 (중복 처리 방지)
            log.setStatus("PROCESSING");
            sagaLogRepository.save(log);

            // 2. 대상 서비스 호출
            switch (log.getTargetService()) {
                case "WALLET":
                    walletClient.capture(log.getPayload());
                    break;
                case "NOTIFICATION":
                    notificationClient.send(log.getPayload());
                    break;
            }

            // 3. 성공
            log.setStatus("SUCCESS");

        } catch (Exception e) {
            handleFailure(log, e);
        }

        sagaLogRepository.save(log);
    }

    private void handleFailure(SagaLog log, Exception e) {
        log.setRetryCount(log.getRetryCount() + 1);
        log.setErrorMessage(e.getMessage());

        if (log.getRetryCount() >= log.getMaxRetries()) {
            // 최대 재시도 초과 → FAILED
            log.setStatus("FAILED");
        } else {
            // 지수 백오프로 다음 재시도 시간 설정
            log.setStatus("PENDING");
            long delaySeconds = 30 * (long) Math.pow(2, log.getRetryCount());
            log.setNextRetryAt(LocalDateTime.now().plusSeconds(delaySeconds));
        }
    }
}
```

### 지수 백오프 (Exponential Backoff)

```
재시도 1회: 30초 후   (30 * 2^0)
재시도 2회: 60초 후   (30 * 2^1)
재시도 3회: 120초 후  (30 * 2^2)
재시도 4회: FAILED 처리

→ 일시적 장애에 대응하면서 서버 부하 방지
```

### 멱등성 (Idempotency) 보장

외부 서비스는 같은 요청이 여러 번 와도 한 번만 처리되어야 합니다.

```java
// Wallet 서비스 (모놀리스)
@PostMapping("/internal/wallets/{walletId}/capture")
public ResponseEntity<?> capture(@RequestBody CaptureRequest request) {
    // 1. 이미 처리된 요청인지 확인 (paymentId로 중복 체크)
    if (transactionRepository.existsByPaymentId(request.getPaymentId())) {
        return ResponseEntity.ok("Already processed");  // 중복 요청 무시
    }

    // 2. 실제 처리
    walletService.capture(request);

    return ResponseEntity.ok("Success");
}
```

---

## 4. keeping 프로젝트 적용 가이드

### QR 결제 vs 포인트 적립 결제

```
┌─────────────────────────────────────────────────────────────┐
│                     QR 결제 (포인트로 음식 구매)              │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  [동기]                        [비동기 - Saga Log]          │
│  잔액 확인 + 차감              알림 발송                    │
│       │                            │                        │
│       ▼                       HTTP + Outbox                 │
│  사용자에게 즉시 응답                                        │
│  "결제 완료!"                                               │
│                                                             │
│  추천: HTTP + Saga Log (구독자 1~2개, 단순함)               │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                   포인트 적립 결제 (현금 결제)               │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  [동기]                        [비동기 - Saga Log]          │
│  현금 결제 확인                포인트 적립                  │
│       │                        알림 발송                    │
│       │                        영수증 생성                  │
│       ▼                            │                        │
│  Saga Log에 저장              스케줄러가 Pending 처리       │
│  (같은 트랜잭션)              + 멱등성 보장                 │
│                                                             │
│  추천: HTTP + Saga Log (Kafka는 과한 인프라)               │
└─────────────────────────────────────────────────────────────┘
```

### 왜 잔액 차감은 동기여야 하나?

```
[문제: Race Condition]

시간  사용자A                    사용자B
────────────────────────────────────────────
0초   잔액 1000원 확인 ✅
1초   결제 완료 응답 반환         잔액 1000원 확인 ✅
2초   (비동기) 차감 대기          결제 완료 응답 반환
3초   차감 → 잔액 0원            (비동기) 차감 대기
4초                              차감 → 잔액 -1000원 💥

[해결: 잔액 확인 + 차감을 원자적 동기 작업으로]
```

### Kafka vs HTTP + Saga Log 선택 기준

```
┌─────────────────────────────────────────────────────┐
│         "같은 이벤트를 몇 개가 구독하나?"             │
├─────────────────────────────────────────────────────┤
│                                                     │
│  1~2개 구독 → HTTP + Saga Log (우리 프로젝트)       │
│                                                     │
│     결제 완료 ──HTTP──► Wallet                       │
│               ──HTTP──► Notification                │
│                                                     │
│  5개 이상 + 계속 늘어남 → Kafka 고려                 │
│                                                     │
│     결제 완료 ──► [Kafka] ──► Wallet                 │
│                          ──► Notification           │
│                          ──► Receipt                │
│                          ──► Analytics              │
│                          ──► (미래 서비스들...)      │
│                                                     │
└─────────────────────────────────────────────────────┘
```

---

## 5. FAILED 상태 처리

### Saga Log 관리 전략

| 상태 | 처리 방법 |
|------|----------|
| SUCCESS | 30일 후 배치 삭제 (CS 대응용 보관) |
| FAILED | 수동 확인 후 보상 트랜잭션 또는 재시도 |
| PENDING (5분+) | Recovery Job이 자동 처리 |

### FAILED 알림 (선택)

```java
@Scheduled(cron = "0 0 9 * * *")  // 매일 오전 9시
public void alertFailedSagas() {
    List<SagaLog> failed = sagaLogRepository.findByStatus("FAILED");

    if (!failed.isEmpty()) {
        // Slack 또는 이메일로 알림
        slackClient.send("#alerts",
            "FAILED Saga " + failed.size() + "건 확인 필요");
    }
}
```

---

## 6. 관련 문서

| 문서 | 설명 |
|------|------|
| 01-msa-basics.md | MSA 기본 개념 |
| 13-msa-expansion-plan.md | Phase 2 확장 계획 (Saga 패턴 적용 예정) |
