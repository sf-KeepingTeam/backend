# MSA 확장 계획 (Phase 2)

**작성일**: 2026-02-03
**현재 상태**: QR-Payment 서비스 분리 완료
**목표**: 전체 서비스 MSA 전환 + Saga 패턴 구현

---

## 1. 현재 아키텍처

```
┌─────────────────┐        ┌─────────────────────────────────┐
│     EC2-A       │        │            EC2-B                │
│   (QR 전용)     │        │         (모놀리스)               │
│                 │        │                                 │
│  ┌───────────┐  │        │  ┌─────────────────────────┐   │
│  │QR-Payment │  │        │  │       Monolith          │   │
│  │  Service  │──┼───────►│  │                         │   │
│  └───────────┘  │  ACL   │  │  ┌─────┐ ┌──────┐      │   │
│                 │        │  │  │Wallet│ │ User │      │   │
│  ┌───────────┐  │        │  │  └─────┘ └──────┘      │   │
│  │   Redis   │  │        │  │  ┌─────┐ ┌──────┐      │   │
│  │(QR 토큰)  │  │        │  │  │Store│ │Mission│     │   │
│  └───────────┘  │        │  │  └─────┘ └──────┘      │   │
└─────────────────┘        │  │  ┌─────┐ ┌──────┐      │   │
                           │  │  │Alarm│ │Saving│      │   │
                           │  │  └─────┘ └──────┘      │   │
                           │  └─────────────────────────┘   │
                           │                                 │
                           │  ┌───────────┐ ┌───────────┐   │
                           │  │   MySQL   │ │   Redis   │   │
                           │  └───────────┘ └───────────┘   │
                           └─────────────────────────────────┘
```

---

## 2. 목표 아키텍처

```
                            ┌─────────────────┐
                            │   API Gateway   │
                            │  (Nginx/Kong)   │
                            └────────┬────────┘
                                     │
        ┌────────────┬───────────────┼───────────────┬────────────┐
        │            │               │               │            │
        ▼            ▼               ▼               ▼            ▼
┌───────────┐ ┌───────────┐ ┌───────────┐ ┌───────────┐ ┌───────────┐
│    QR     │ │  Payment  │ │  Wallet   │ │   User    │ │   Store   │
│  Service  │ │  Service  │ │  Service  │ │  Service  │ │  Service  │
└─────┬─────┘ └─────┬─────┘ └─────┬─────┘ └─────┬─────┘ └─────┬─────┘
      │             │             │             │             │
      └─────────────┴──────┬──────┴─────────────┴─────────────┘
                           │
                    ┌──────▼──────┐
                    │ Message Bus │
                    │  (Kafka)    │
                    └─────────────┘
```

---

## 3. 서비스 분리 우선순위

### 3.1 분리 대상 분석

| 서비스 | 현재 위치 | 분리 필요성 | 복잡도 | 우선순위 |
|--------|----------|------------|--------|---------|
| QR | EC2-A ✅ | 완료 | - | 완료 |
| **Payment** | Monolith | 높음 (Saga 필요) | 높음 | **1순위** |
| **Wallet** | Monolith | 높음 (핵심 도메인) | 중간 | **2순위** |
| Store | Monolith | 중간 | 낮음 | 3순위 |
| User | Monolith | 중간 | 중간 | 4순위 |
| Mission | Monolith | 낮음 | 낮음 | 5순위 |
| Saving | Monolith | 낮음 | 중간 | 6순위 |
| Alarm | Monolith | 낮음 | 낮음 | 7순위 |

### 3.2 분리 순서 결정 이유

```
1순위: Payment (결제)
- QR과 함께 결제 플로우 완성
- Saga 패턴 구현의 시작점
- 트랜잭션 관리가 가장 복잡

2순위: Wallet (지갑)
- 부하 테스트에서 가장 느린 서비스
- Payment와 밀접한 관계 (Saga 대상)
- 독립 스케일링 필요

3순위 이후: 비즈니스 우선순위에 따라 결정
```

---

## 4. Phase 2: Payment + Wallet 분리

### 4.1 결제 플로우 분석

```
현재 모놀리스 결제 플로우:

┌──────────┐    ┌──────────────────────────────────────────┐
│  Client  │───►│              Monolith                     │
└──────────┘    │                                          │
                │  1. QR 검증                               │
                │      │                                    │
                │      ▼                                    │
                │  2. 잔액 확인 (Wallet)                    │
                │      │                                    │
                │      ▼                                    │
                │  3. 잔액 차감 (Wallet)                    │
                │      │                                    │
                │      ▼                                    │
                │  4. 거래 기록 생성 (Payment)              │
                │      │                                    │
                │      ▼                                    │
                │  5. 알림 발송 (Alarm)                     │
                │                                          │
                └──────────────────────────────────────────┘

문제점: 모든 단계가 하나의 DB 트랜잭션
       → 하나라도 실패하면 전체 롤백
       → 분산 환경에서는 불가능!
```

### 4.2 MSA 결제 플로우 (Saga 적용)

```
MSA 결제 플로우:

┌──────────┐
│  Client  │
└────┬─────┘
     │
     ▼
┌─────────────────┐
│   API Gateway   │
└────────┬────────┘
         │
         ▼
┌─────────────────┐     ┌─────────────────┐
│  QR Service     │────►│ Payment Service │
│  (QR 검증)      │     │ (결제 오케스트라)│
└─────────────────┘     └────────┬────────┘
                                 │
                    ┌────────────┼────────────┐
                    │            │            │
                    ▼            ▼            ▼
             ┌───────────┐ ┌───────────┐ ┌───────────┐
             │  Wallet   │ │  Payment  │ │   Alarm   │
             │  Service  │ │   Record  │ │  Service  │
             │ (잔액차감) │ │ (거래기록) │ │  (알림)   │
             └───────────┘ └───────────┘ └───────────┘
```

---

## 5. Saga 패턴 설계

### 5.1 Saga 패턴이란?

```
기존 모놀리스:
┌─────────────────────────────────────────┐
│           단일 DB 트랜잭션               │
│                                         │
│  BEGIN TRANSACTION                      │
│    1. 잔액 차감                          │
│    2. 거래 기록                          │
│    3. 알림 발송                          │
│  COMMIT (또는 전체 ROLLBACK)             │
│                                         │
└─────────────────────────────────────────┘

MSA + Saga:
┌─────────────────────────────────────────┐
│           분산 트랜잭션                   │
│                                         │
│  Step 1: Wallet - 잔액 차감              │
│      ↓ 성공                              │
│  Step 2: Payment - 거래 기록             │
│      ↓ 실패!                             │
│  보상 트랜잭션: Wallet - 잔액 복원 ←──────│
│                                         │
└─────────────────────────────────────────┘
```

### 5.2 Saga 유형 비교

| 유형 | 설명 | 장점 | 단점 |
|------|------|------|------|
| **Orchestration** | 중앙 조정자가 순서 제어 | 플로우 명확, 디버깅 쉬움 | 단일 장애점 |
| Choreography | 각 서비스가 이벤트 발행/구독 | 느슨한 결합 | 플로우 추적 어려움 |

**권장: Orchestration Saga** (처음 도입 시 더 이해하기 쉬움)

### 5.3 결제 Saga 설계

```
┌─────────────────────────────────────────────────────────────────┐
│                    Payment Saga Orchestrator                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  [정상 플로우]                                                    │
│                                                                  │
│  1. START ─────► Wallet: Reserve (잔액 예약)                     │
│                      │                                           │
│                      ▼ 성공                                       │
│  2. ──────────► Payment: Create (거래 기록)                       │
│                      │                                           │
│                      ▼ 성공                                       │
│  3. ──────────► Wallet: Confirm (예약 확정)                       │
│                      │                                           │
│                      ▼ 성공                                       │
│  4. ──────────► Alarm: Send (알림)                                │
│                      │                                           │
│                      ▼                                            │
│  5. COMPLETE                                                      │
│                                                                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  [보상 플로우 - Step 2 실패 시]                                    │
│                                                                  │
│  1. Wallet: Reserve ✅                                            │
│  2. Payment: Create ❌ 실패!                                      │
│       │                                                          │
│       ▼                                                          │
│  보상: Wallet: Cancel (예약 취소) ◄────────────────────────────   │
│       │                                                          │
│       ▼                                                          │
│  FAILED                                                          │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 5.4 Saga 상태 다이어그램

```
                    ┌─────────────────┐
                    │     STARTED     │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │ WALLET_RESERVED │
                    └────────┬────────┘
                             │
              ┌──────────────┴──────────────┐
              │                             │
     ┌────────▼────────┐           ┌────────▼────────┐
     │ PAYMENT_CREATED │           │  WALLET_FAILED  │
     └────────┬────────┘           └────────┬────────┘
              │                             │
     ┌────────▼────────┐           ┌────────▼────────┐
     │WALLET_CONFIRMED │           │     FAILED      │
     └────────┬────────┘           └─────────────────┘
              │
     ┌────────▼────────┐
     │   ALARM_SENT    │
     └────────┬────────┘
              │
     ┌────────▼────────┐
     │    COMPLETED    │
     └─────────────────┘
```

---

## 6. 기술 스택 선택

### 6.1 메시지 브로커 비교

| 기술 | 장점 | 단점 | 적합성 |
|------|------|------|--------|
| **Kafka** | 고성능, 내구성, 순서 보장 | 복잡한 설정 | ⭐⭐⭐ |
| RabbitMQ | 간단한 설정, 다양한 패턴 | 대용량 시 성능 | ⭐⭐ |
| Redis Streams | 이미 사용 중, 간단 | 내구성 낮음 | ⭐ |
| AWS SQS | 관리형, 간단 | 순서 보장 어려움 | ⭐⭐ |

**권장: Kafka** (대용량 처리, 이벤트 소싱 가능)

### 6.2 Saga 프레임워크 비교

| 프레임워크 | 장점 | 단점 |
|-----------|------|------|
| **Axon Framework** | 완성도 높음, CQRS 지원 | 러닝커브 |
| Eventuate Tram | 가벼움, Saga 특화 | 문서 부족 |
| 직접 구현 | 커스텀 가능 | 개발 비용 |

**권장: 직접 구현 (학습 목적) → Axon (프로덕션)**

---

## 7. 구현 계획

### 7.1 Phase 2-A: Payment Service 분리

```
목표: Payment 도메인을 별도 서비스로 분리

┌─────────────────────────────────────────┐
│         services/payment-service/        │
├─────────────────────────────────────────┤
│  src/main/java/                          │
│  └── com/ssafy/keeping/payment/         │
│      ├── PaymentApplication.java        │
│      ├── domain/                        │
│      │   ├── Payment.java               │
│      │   └── PaymentStatus.java         │
│      ├── saga/                          │
│      │   ├── PaymentSaga.java           │
│      │   ├── PaymentSagaState.java      │
│      │   └── SagaOrchestrator.java      │
│      ├── acl/                           │
│      │   ├── WalletClient.java          │
│      │   └── AlarmClient.java           │
│      ├── event/                         │
│      │   ├── PaymentCreatedEvent.java   │
│      │   └── PaymentFailedEvent.java    │
│      └── controller/                    │
│          └── PaymentController.java     │
└─────────────────────────────────────────┘
```

### 7.2 Phase 2-B: Wallet Service 분리

```
목표: Wallet 도메인을 별도 서비스로 분리

┌─────────────────────────────────────────┐
│         services/wallet-service/         │
├─────────────────────────────────────────┤
│  src/main/java/                          │
│  └── com/ssafy/keeping/wallet/          │
│      ├── WalletApplication.java         │
│      ├── domain/                        │
│      │   ├── Wallet.java                │
│      │   ├── WalletTransaction.java     │
│      │   └── ReservationStatus.java     │
│      ├── service/                       │
│      │   ├── WalletService.java         │
│      │   └── ReservationService.java    │
│      ├── event/                         │
│      │   ├── BalanceReservedEvent.java  │
│      │   ├── BalanceConfirmedEvent.java │
│      │   └── BalanceCancelledEvent.java │
│      └── controller/                    │
│          ├── WalletController.java      │
│          └── InternalWalletController.java│
└─────────────────────────────────────────┘
```

### 7.3 Phase 2-C: Saga 통합

```
목표: Kafka 기반 Saga 패턴 구현

┌─────────────────────────────────────────────────────────────┐
│                         Kafka Topics                         │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  payment-saga-commands                                       │
│  ├── RESERVE_BALANCE                                        │
│  ├── CREATE_PAYMENT                                         │
│  ├── CONFIRM_BALANCE                                        │
│  └── SEND_ALARM                                             │
│                                                              │
│  payment-saga-events                                         │
│  ├── BALANCE_RESERVED                                       │
│  ├── BALANCE_RESERVE_FAILED                                 │
│  ├── PAYMENT_CREATED                                        │
│  ├── PAYMENT_CREATE_FAILED                                  │
│  ├── BALANCE_CONFIRMED                                      │
│  └── ALARM_SENT                                             │
│                                                              │
│  payment-saga-compensations                                  │
│  ├── CANCEL_BALANCE_RESERVATION                             │
│  └── CANCEL_PAYMENT                                         │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

---

## 8. 인프라 계획

### 8.1 최종 서버 구성

```
┌────────────────────────────────────────────────────────────────┐
│                        AWS 인프라                               │
├────────────────────────────────────────────────────────────────┤
│                                                                │
│  ┌──────────────────┐                                         │
│  │  EC2-Gateway     │  API Gateway (Nginx/Kong)               │
│  │  t3.small        │                                         │
│  └────────┬─────────┘                                         │
│           │                                                    │
│  ┌────────┴────────────────────────────────────────────┐      │
│  │                                                      │      │
│  ▼                  ▼                  ▼               ▼      │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐      │
│  │ EC2-QR   │  │EC2-Payment│ │EC2-Wallet│  │EC2-Mono  │      │
│  │ t3.small │  │ t3.small │  │ t3.small │  │t3.medium │      │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘      │
│                                                                │
│  ┌────────────────────────────────────────────────────────┐   │
│  │                     공유 인프라                          │   │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐             │   │
│  │  │   RDS    │  │ ElastiCache│ │  MSK     │             │   │
│  │  │ (MySQL)  │  │  (Redis)  │  │ (Kafka)  │             │   │
│  │  └──────────┘  └──────────┘  └──────────┘             │   │
│  └────────────────────────────────────────────────────────┘   │
│                                                                │
└────────────────────────────────────────────────────────────────┘

비용 예상:
- EC2 x 5: ~$75/월
- RDS: ~$25/월
- ElastiCache: ~$15/월
- MSK (Kafka): ~$50/월
- 총: ~$165/월
```

### 8.2 단계별 인프라 확장

```
현재 (Phase 1 완료):
┌──────────┐  ┌──────────┐
│  EC2-A   │  │  EC2-B   │
│   (QR)   │  │(Monolith)│
└──────────┘  └──────────┘
비용: ~$45/월

Phase 2 (Payment + Wallet):
┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐
│  EC2-A   │  │  EC2-C   │  │  EC2-D   │  │  EC2-B   │
│   (QR)   │  │(Payment) │  │ (Wallet) │  │(Monolith)│
└──────────┘  └──────────┘  └──────────┘  └──────────┘
                    + Kafka 추가
비용: ~$120/월

Phase 3 (전체 분리):
별도 Gateway + 모든 서비스 분리 + 관리형 서비스
비용: ~$200/월
```

---

## 9. 상세 구현 가이드

### 9.1 Wallet 잔액 예약 패턴

```java
// WalletService.java
@Service
public class WalletService {

    // 1단계: 잔액 예약 (실제 차감 X)
    public ReservationResult reserve(Long walletId, BigDecimal amount, String sagaId) {
        Wallet wallet = walletRepository.findById(walletId)
            .orElseThrow(() -> new WalletNotFoundException(walletId));

        if (wallet.getAvailableBalance().compareTo(amount) < 0) {
            return ReservationResult.failure("잔액 부족");
        }

        // 예약 금액만큼 available에서 차감 (실제 balance는 유지)
        wallet.reserve(amount);

        // 예약 정보 저장
        Reservation reservation = Reservation.create(sagaId, walletId, amount);
        reservationRepository.save(reservation);

        return ReservationResult.success(reservation.getId());
    }

    // 2단계: 예약 확정 (실제 차감)
    public void confirm(String reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
            .orElseThrow();

        Wallet wallet = walletRepository.findById(reservation.getWalletId())
            .orElseThrow();

        wallet.confirmReservation(reservation.getAmount());
        reservation.confirm();
    }

    // 보상: 예약 취소
    public void cancel(String reservationId) {
        Reservation reservation = reservationRepository.findById(reservationId)
            .orElseThrow();

        Wallet wallet = walletRepository.findById(reservation.getWalletId())
            .orElseThrow();

        wallet.cancelReservation(reservation.getAmount());
        reservation.cancel();
    }
}
```

### 9.2 Saga Orchestrator 구현

```java
// PaymentSagaOrchestrator.java
@Service
public class PaymentSagaOrchestrator {

    private final KafkaTemplate<String, SagaCommand> kafkaTemplate;
    private final PaymentSagaRepository sagaRepository;

    // Saga 시작
    public String startPaymentSaga(PaymentRequest request) {
        PaymentSaga saga = PaymentSaga.create(request);
        sagaRepository.save(saga);

        // Step 1: 잔액 예약 명령 발행
        kafkaTemplate.send("payment-saga-commands",
            new ReserveBalanceCommand(saga.getId(), request.getWalletId(), request.getAmount()));

        return saga.getId();
    }

    // 이벤트 수신 및 다음 단계 진행
    @KafkaListener(topics = "payment-saga-events")
    public void handleEvent(SagaEvent event) {
        PaymentSaga saga = sagaRepository.findById(event.getSagaId())
            .orElseThrow();

        switch (event.getType()) {
            case BALANCE_RESERVED:
                saga.onBalanceReserved(event.getReservationId());
                // Step 2: 결제 기록 생성
                kafkaTemplate.send("payment-saga-commands",
                    new CreatePaymentCommand(saga.getId(), saga.getPaymentDetails()));
                break;

            case PAYMENT_CREATED:
                saga.onPaymentCreated(event.getPaymentId());
                // Step 3: 예약 확정
                kafkaTemplate.send("payment-saga-commands",
                    new ConfirmBalanceCommand(saga.getId(), saga.getReservationId()));
                break;

            case BALANCE_CONFIRMED:
                saga.complete();
                // Step 4: 알림 발송 (비동기, 실패해도 OK)
                kafkaTemplate.send("payment-saga-commands",
                    new SendAlarmCommand(saga.getId(), saga.getAlarmDetails()));
                break;

            case BALANCE_RESERVE_FAILED:
            case PAYMENT_CREATE_FAILED:
                handleFailure(saga, event);
                break;
        }

        sagaRepository.save(saga);
    }

    // 보상 트랜잭션
    private void handleFailure(PaymentSaga saga, SagaEvent event) {
        saga.fail(event.getReason());

        // 이미 완료된 단계들 롤백
        if (saga.getReservationId() != null) {
            kafkaTemplate.send("payment-saga-compensations",
                new CancelReservationCommand(saga.getId(), saga.getReservationId()));
        }
    }
}
```

### 9.3 Kafka 설정

```yaml
# application.yml (Payment Service)
spring:
  kafka:
    bootstrap-servers: kafka:9092
    consumer:
      group-id: payment-saga-group
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.ssafy.keeping.*"
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
```

---

## 10. 테스트 전략

### 10.1 Saga 테스트 시나리오

| 시나리오 | 설명 | 예상 결과 |
|---------|------|----------|
| 정상 결제 | 모든 단계 성공 | COMPLETED |
| 잔액 부족 | Step 1 실패 | FAILED (보상 없음) |
| 결제 기록 실패 | Step 2 실패 | FAILED + 잔액 복원 |
| 알림 실패 | Step 4 실패 | COMPLETED (알림은 optional) |
| Wallet 서비스 다운 | Step 1 타임아웃 | FAILED (재시도 후) |

### 10.2 부하 테스트

```javascript
// saga-payment.js (k6)
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
    stages: [
        { duration: '1m', target: 50 },
        { duration: '3m', target: 50 },
        { duration: '1m', target: 0 },
    ],
};

export default function () {
    // 결제 요청
    const paymentRes = http.post(`${BASE_URL}/api/payments`, JSON.stringify({
        walletId: Math.floor(Math.random() * 1000) + 40001,
        amount: 1000,
        storeId: 30001,
    }), {
        headers: { 'Content-Type': 'application/json' },
    });

    check(paymentRes, {
        'payment accepted': (r) => r.status === 202, // Saga는 비동기
    });

    const sagaId = JSON.parse(paymentRes.body).sagaId;

    // Saga 완료 확인 (폴링)
    let completed = false;
    for (let i = 0; i < 10 && !completed; i++) {
        sleep(0.5);
        const statusRes = http.get(`${BASE_URL}/api/payments/saga/${sagaId}/status`);
        completed = JSON.parse(statusRes.body).status === 'COMPLETED';
    }

    check(null, {
        'saga completed': () => completed,
    });
}
```

---

## 11. 롤백 계획

### 11.1 서비스 레벨 롤백

```
문제 발생 시:

1. Nginx에서 트래픽 전환
   location /api/payments {
       proxy_pass http://monolith;  # 새 서비스 → 모놀리스
   }

2. 새 서비스 컨테이너 중지
   docker-compose stop payment-service

3. 원인 분석 후 수정
```

### 11.2 Saga 실패 복구

```
Saga 실패 시 데이터 정합성:

1. saga_status 테이블에서 FAILED 상태 조회
2. 각 saga의 완료된 단계 확인
3. 수동 보상 트랜잭션 실행 (필요시)

SELECT * FROM payment_saga
WHERE status = 'FAILED'
AND created_at > NOW() - INTERVAL 1 HOUR;
```

---

## 12. 일정 계획

```
┌─────────────────────────────────────────────────────────────────┐
│                      Phase 2 일정                                │
├──────────┬──────────────────────────────────────────────────────┤
│   주차   │                    작업                               │
├──────────┼──────────────────────────────────────────────────────┤
│  Week 1  │ Payment Service 분리 (코드 이전, ACL 구현)            │
├──────────┼──────────────────────────────────────────────────────┤
│  Week 2  │ Wallet Service 분리 + 예약 패턴 구현                  │
├──────────┼──────────────────────────────────────────────────────┤
│  Week 3  │ Kafka 설정 + Saga Orchestrator 구현                  │
├──────────┼──────────────────────────────────────────────────────┤
│  Week 4  │ 통합 테스트 + 부하 테스트                             │
├──────────┼──────────────────────────────────────────────────────┤
│  Week 5  │ Canary 배포 (5% → 25% → 50% → 100%)                  │
└──────────┴──────────────────────────────────────────────────────┘
```

---

## 13. 성공 기준

| 항목 | 목표 | 측정 방법 |
|------|------|----------|
| 결제 성공률 | > 99.9% | Saga 완료율 모니터링 |
| 결제 응답시간 | p95 < 500ms | k6 부하 테스트 |
| 데이터 정합성 | 100% | 잔액 차감 = 거래 기록 검증 |
| 장애 격리 | Wallet 다운 시 QR 정상 | 장애 주입 테스트 |
| 롤백 시간 | < 1분 | Nginx 설정 변경 |

---

## 14. 다음 단계

Phase 2 완료 후:

```
Phase 3: Store + User 분리
Phase 4: Mission + Saving 분리
Phase 5: 전체 Kubernetes 전환 (선택)
```

---

## 부록: 참고 자료

### A. Saga 패턴 학습 자료
- [Microservices.io - Saga Pattern](https://microservices.io/patterns/data/saga.html)
- [Chris Richardson - Saga Pattern](https://chrisrichardson.net/post/sagas/2019/08/15/saga-orchestration.html)

### B. Kafka 학습 자료
- [Kafka 공식 문서](https://kafka.apache.org/documentation/)
- [Spring Kafka 가이드](https://spring.io/projects/spring-kafka)

### C. 관련 문서
| 문서 | 설명 |
|------|------|
| 01-strangler-fig-pattern.md | Strangler Fig 패턴 |
| 09-nginx-architecture-guide.md | Nginx 가이드 |
| 12-server-separation-final-report.md | 서버 분리 최종 보고서 |
