# MSA Migration Architecture Guide

> QR Service 분리를 통한 마이크로서비스 아키텍처 마이그레이션 상세 가이드

---

## 목차

1. [연관관계 분리 (@ManyToOne → ID 참조)](#1-연관관계-분리-manytoone--id-참조)
2. [ACL 패턴 (Anti-Corruption Layer)](#2-acl-패턴-anti-corruption-layer)
3. [물리적 서버 분리](#3-물리적-서버-분리)

---

## 1. 연관관계 분리 (@ManyToOne → ID 참조)

### 1.1 개요

```
@ManyToOne → ID 참조 (14개 엔티티)
→ DB 분리 가능, 불필요한 JOIN 제거
```

마이크로서비스 아키텍처에서 서비스 간 데이터베이스를 독립적으로 운영하기 위해, JPA의 `@ManyToOne` 객체 참조를 단순 ID 참조로 변경하는 패턴입니다.

### 1.2 특징

| 구분 | @ManyToOne (모놀리스) | ID 참조 (MSA) |
|------|----------------------|---------------|
| **관계 표현** | 객체 그래프 탐색 | 단순 Long/UUID 필드 |
| **데이터 조회** | JPA가 자동 JOIN | 별도 API 호출 필요 |
| **참조 무결성** | DB 외래키 제약 | 애플리케이션 레벨 검증 |
| **결합도** | 강한 결합 (컴파일 타임) | 느슨한 결합 (런타임) |
| **트랜잭션** | 단일 트랜잭션 | 분산 트랜잭션/Saga |
| **배포** | 함께 배포 | 독립 배포 |

### 1.3 장점

```
┌─────────────────────────────────────────────────────────────────┐
│                         장점                                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. DB 분리 가능                                                 │
│     ┌─────────────┐         ┌─────────────┐                     │
│     │ payment_    │         │  keeping    │                     │
│     │ service DB  │  ←X→    │     DB      │                     │
│     │             │  분리    │             │                     │
│     │ • payment_  │         │ • wallet    │                     │
│     │   intent    │         │ • customer  │                     │
│     │ • idempot.. │         │ • store     │                     │
│     └─────────────┘         └─────────────┘                     │
│                                                                  │
│  2. 불필요한 JOIN 제거                                           │
│     - N+1 쿼리 문제 근본적 해결                                  │
│     - 쿼리 복잡도 감소                                           │
│     - 인덱스 최적화 용이                                         │
│                                                                  │
│  3. 독립적 스키마 진화                                           │
│     - 서비스별 DDL 변경 독립                                     │
│     - 마이그레이션 영향 범위 최소화                               │
│                                                                  │
│  4. 확장성 향상                                                  │
│     - 서비스별 DB 샤딩 가능                                      │
│     - 읽기 복제본 독립 구성                                       │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 1.4 단점

```
┌─────────────────────────────────────────────────────────────────┐
│                         단점                                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. 참조 무결성 보장 어려움                                       │
│     - DB 레벨 FK 제약 불가                                       │
│     - 고아 데이터 발생 가능                                       │
│     - 애플리케이션에서 검증 로직 필요                              │
│                                                                  │
│  2. 조회 복잡도 증가                                             │
│     - 연관 데이터 조회 시 추가 API 호출                           │
│     - 네트워크 레이턴시 발생                                      │
│     - 캐싱 전략 필요                                             │
│                                                                  │
│  3. 일관성 관리                                                  │
│     - 분산 트랜잭션 필요 (Saga 패턴)                              │
│     - 최종 일관성(Eventual Consistency) 수용                     │
│     - 보상 트랜잭션 로직 구현 필요                                │
│                                                                  │
│  4. 코드 복잡도 증가                                             │
│     - DTO 변환 로직 추가                                         │
│     - 스냅샷 데이터 관리                                          │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 1.5 분리 예시 (프로젝트 코드)

#### 1.5.1 모놀리스 방식 (Before) - Transaction 엔티티

**파일:** `src/main/java/com/ssafy/keeping/domain/payment/transactions/model/Transaction.java`

```java
@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "transaction_id")
    private Long transactionId;

    // @ManyToOne으로 다른 엔티티 직접 참조 (강한 결합)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "wallet_id", nullable = false)
    private Wallet wallet;                    // ← 객체 참조

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;                // ← 객체 참조

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "store_id", nullable = false)
    private Store store;                      // ← 객체 참조

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false)
    private TransactionType transactionType;

    @Column(name = "amount", nullable = false)
    private Long amount;
}
```

**문제점:**
- Wallet, Customer, Store 엔티티와 컴파일 타임 의존성
- 동일 DB 내에서만 동작 가능
- 조회 시 자동 JOIN 발생 (N+1 문제 가능)

#### 1.5.2 MSA 방식 (After) - PaymentIntent 엔티티

**파일:** `services/qr-service/src/main/java/com/ssafy/keeping/qr/domain/intent/model/PaymentIntent.java`

```java
@Entity
@Table(name = "payment_intent")
public class PaymentIntent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "intent_id")
    private Long intentId;

    @Column(name = "public_id", nullable = false, unique = true)
    private UUID publicId;

    // ID만 저장 (느슨한 결합) - 다른 서비스 엔티티와 독립
    @Column(name = "customer_id", nullable = false)
    private Long customerId;                  // ← ID만 참조

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;                    // ← ID만 참조

    @Column(name = "store_id", nullable = false)
    private Long storeId;                     // ← ID만 참조

    @Column(nullable = false)
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private PaymentStatus status;

    // 낙관적 락으로 동시성 제어
    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
```

**장점:**
- Customer, Wallet, Store 서비스와 독립적
- 별도 DB(payment_service)에서 운영 가능
- JOIN 없이 단순 쿼리로 조회

#### 1.5.3 TransactionItem vs PaymentIntentItem 비교

**모놀리스 - TransactionItem:**
```java
// src/main/java/.../transactions/model/TransactionItem.java

@Entity
@Table(name = "transaction_items")
public class TransactionItem {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false)
    private Transaction transaction;          // ← 같은 서비스 내 참조 OK

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "menu_id")
    private Menu menu;                        // ← 다른 도메인 직접 참조 (문제)

    @Column(name = "menu_name_snapshot")
    private String menuNameSnapshot;          // 스냅샷도 있지만 Menu 객체도 참조

    @Column(name = "menu_price_snapshot")
    private Long menuPriceSnapshot;
}
```

**QR Service - PaymentIntentItem:**
```java
// services/qr-service/src/main/java/.../intent/model/PaymentIntentItem.java

@Entity
@Table(name = "payment_intent_item")
public class PaymentIntentItem {

    // 같은 서비스 내 엔티티만 @ManyToOne 사용
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "intent_id", nullable = false)
    private PaymentIntent intent;             // ← 같은 서비스 내 참조 OK

    // 다른 서비스(Menu)는 ID만 참조 + 스냅샷 저장
    @Column(name = "menu_id", nullable = false)
    private Long menuId;                      // ← ID만 참조

    @Column(name = "menu_name_snap", nullable = false)
    private String menuNameSnap;              // ← 스냅샷 (시점 데이터 보존)

    @Column(name = "unit_price_snap", nullable = false)
    private long unitPriceSnap;               // ← 스냅샷 (가격 변동 대응)

    @Column(nullable = false)
    private int quantity;
}
```

### 1.6 스냅샷 패턴

서비스 간 ID 참조 시, 조회 시점의 데이터를 보존하기 위해 스냅샷을 함께 저장합니다.

```
┌─────────────────────────────────────────────────────────────────┐
│                    스냅샷 패턴 (Snapshot Pattern)                │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  결제 시점: 2024-01-15 14:30                                     │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ PaymentIntentItem                                          │ │
│  │   menuId: 42                                               │ │
│  │   menuNameSnap: "아메리카노"     ← 결제 시점 메뉴명          │ │
│  │   unitPriceSnap: 4500           ← 결제 시점 가격            │ │
│  │   quantity: 2                                              │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  이후 메뉴 변경 (2024-01-20):                                    │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │ Menu (모놀리스)                                            │ │
│  │   menuId: 42                                               │ │
│  │   name: "HOT 아메리카노"        ← 변경됨                    │ │
│  │   price: 5000                   ← 인상됨                    │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                  │
│  → 결제 내역 조회 시 스냅샷 데이터 사용 (정확한 거래 기록 보존)   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 1.7 프로젝트 내 적용된 14개 엔티티 패턴

| 서비스 | 엔티티 | 참조 방식 | 대상 |
|--------|--------|-----------|------|
| QR Service | PaymentIntent | ID 참조 | customerId, walletId, storeId |
| QR Service | PaymentIntentItem | ID 참조 + 스냅샷 | menuId |
| QR Service | IdempotencyKey | ID 참조 | actorId (customer/owner) |
| QR Service | QrToken (Redis) | ID 참조 | customerId, walletId |

---

## 2. ACL 패턴 (Anti-Corruption Layer)

### 2.1 개요

```
QR Service → WalletClient → 모놀리스
=> 서비스 간 결합도 제거, 변경 격리
```

ACL(Anti-Corruption Layer)은 외부 시스템의 모델과 내부 도메인 모델 사이에 번역 계층을 두어, 외부 변경이 내부에 영향을 미치지 않도록 격리하는 패턴입니다.

### 2.2 특징

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              ACL 패턴 구조                                       │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│  ┌──────────────────────────────────────────────────────────────────────────┐   │
│  │                         QR Service                                        │   │
│  │                                                                           │   │
│  │   Domain Layer                                                            │   │
│  │   ┌─────────────────────────────────────────────────────────────────┐    │   │
│  │   │  PaymentIntent    QrToken    IdempotencyKey    FundsResult      │    │   │
│  │   │  (순수 도메인 모델 - 외부 의존성 없음)                            │    │   │
│  │   └─────────────────────────────────────────────────────────────────┘    │   │
│  │                              │                                            │   │
│  │                              ▼                                            │   │
│  │   Service Layer                                                           │   │
│  │   ┌─────────────────────────────────────────────────────────────────┐    │   │
│  │   │  PaymentIntentService ──► FundsService (도메인 ↔ ACL 어댑터)     │    │   │
│  │   └─────────────────────────────────────────────────────────────────┘    │   │
│  │                              │                                            │   │
│  │                              ▼                                            │   │
│  │   ╔═════════════════════════════════════════════════════════════════╗    │   │
│  │   ║                    ACL LAYER                                     ║    │   │
│  │   ║  ┌─────────────┐ ┌────────────┐ ┌──────────┐ ┌───────────────┐  ║    │   │
│  │   ║  │WalletClient │ │CustomerCli.│ │MenuClient│ │NotificationCli│  ║    │   │
│  │   ║  └─────────────┘ └────────────┘ └──────────┘ └───────────────┘  ║    │   │
│  │   ║                                                                  ║    │   │
│  │   ║  ACL DTOs (격리된 외부 모델)                                      ║    │   │
│  │   ║  ┌────────────────────────────────────────────────────────────┐ ║    │   │
│  │   ║  │ FundsCaptureRequest │ FundsResponse │ CustomerResponse │...│ ║    │   │
│  │   ║  └────────────────────────────────────────────────────────────┘ ║    │   │
│  │   ╚═════════════════════════════════════════════════════════════════╝    │   │
│  │                              │                                            │   │
│  └──────────────────────────────┼────────────────────────────────────────────┘   │
│                                 │ HTTP + X-Internal-Auth                         │
│                                 ▼                                                │
│  ┌──────────────────────────────────────────────────────────────────────────┐   │
│  │                           Monolith                                        │   │
│  │   Internal API (/internal/*)                                              │   │
│  │   ┌────────────────────────────────────────────────────────────────┐     │   │
│  │   │ InternalWalletController │ InternalCustomerController │ ...    │     │   │
│  │   └────────────────────────────────────────────────────────────────┘     │   │
│  └──────────────────────────────────────────────────────────────────────────┘   │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### 2.3 장점

```
┌─────────────────────────────────────────────────────────────────┐
│                         장점                                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. 결합도 제거                                                  │
│     - QR Service는 Monolith의 Wallet, Transaction 엔티티 모름    │
│     - 오직 ACL DTO로만 통신                                      │
│     - 컴파일 타임 의존성 제거                                    │
│                                                                  │
│  2. 변경 격리 (Change Isolation)                                 │
│     ┌─────────────────────────────────────────────────────────┐ │
│     │ Monolith 내부 변경                                       │ │
│     │   • Wallet 필드 추가/삭제                                │ │
│     │   • Transaction 로직 변경                                │ │
│     │   • DB 스키마 마이그레이션                               │ │
│     │                    ↓                                     │ │
│     │ Internal API 계약만 유지하면                              │ │
│     │                    ↓                                     │ │
│     │ QR Service 코드 변경 불필요!                              │ │
│     └─────────────────────────────────────────────────────────┘ │
│                                                                  │
│  3. 독립적 테스트                                                │
│     - ACL을 Mock으로 대체하여 단위 테스트 용이                   │
│     - 통합 테스트 범위 명확                                      │
│                                                                  │
│  4. 명확한 경계                                                  │
│     - 서비스 간 계약(Contract) 명시적 정의                       │
│     - API 버전 관리 용이                                         │
│                                                                  │
│  5. 장애 격리 준비                                               │
│     - Circuit Breaker 추가 지점 명확                             │
│     - Fallback 로직 구현 용이                                    │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 2.4 단점

```
┌─────────────────────────────────────────────────────────────────┐
│                         단점                                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. 네트워크 오버헤드                                            │
│     - HTTP 호출 레이턴시 추가                                    │
│     - 직렬화/역직렬화 비용                                       │
│     - 현재 설정: 연결 5초, 읽기 5초 타임아웃                      │
│                                                                  │
│  2. 코드 중복                                                    │
│     - DTO 정의 양쪽에서 관리                                     │
│     - 변환 로직 추가 작성 필요                                   │
│                                                                  │
│  3. 디버깅 복잡도                                                │
│     - 분산 추적 필요                                             │
│     - 로그 수집/분석 도구 필요                                   │
│                                                                  │
│  4. 트랜잭션 일관성                                              │
│     - 분산 트랜잭션 직접 불가                                    │
│     - 최종 일관성 또는 Saga 패턴 필요                            │
│                                                                  │
│  5. 추가 인프라                                                  │
│     - Internal API 인증 관리                                     │
│     - API Gateway 구성 필요                                      │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 2.5 분리 예시 (프로젝트 코드)

#### 2.5.1 ACL Client - WalletClient

**파일:** `services/qr-service/src/main/java/com/ssafy/keeping/qr/acl/WalletClient.java`

```java
@Component
public class WalletClient {

    private final RestTemplate restTemplate;

    @Value("${monolith.url}")
    private String monolithUrl;           // http://monolith:8080

    @Value("${internal.auth-token}")
    private String internalAuthToken;     // internal-service-token-12345

    /**
     * 지갑 잔액 조회 (매장별)
     */
    public BigDecimal getBalance(Long walletId, Long storeId) {
        String url = monolithUrl + "/internal/wallets/" + walletId
                   + "/stores/" + storeId + "/balance";

        HttpEntity<Void> entity = new HttpEntity<>(createHeaders());

        ResponseEntity<WalletBalanceResponse> response = restTemplate.exchange(
            url, HttpMethod.GET, entity, WalletBalanceResponse.class
        );

        return response.getBody().getBalance();
    }

    /**
     * 자금 캡처 (결제 시 잔액 차감)
     */
    public FundsResponse capture(FundsCaptureRequest request) {
        String url = monolithUrl + "/internal/wallets/" + request.getWalletId()
                   + "/stores/" + request.getStoreId() + "/capture";

        HttpEntity<FundsCaptureRequest> entity = new HttpEntity<>(request, createHeaders());

        ResponseEntity<FundsResponse> response = restTemplate.exchange(
            url, HttpMethod.POST, entity, FundsResponse.class
        );

        return response.getBody();
    }

    /**
     * 자금 복원 (결제 취소 시)
     */
    public void restore(Long walletId, Long storeId, Long amount) {
        String url = monolithUrl + "/internal/wallets/" + walletId
                   + "/stores/" + storeId + "/restore";

        Map<String, Long> body = Map.of("amount", amount);
        HttpEntity<Map<String, Long>> entity = new HttpEntity<>(body, createHeaders());

        restTemplate.exchange(url, HttpMethod.POST, entity, Void.class);
    }

    /**
     * Internal API 인증 헤더 생성
     */
    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Auth", internalAuthToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
```

#### 2.5.2 ACL DTO - 요청/응답 모델

**파일:** `services/qr-service/src/main/java/com/ssafy/keeping/qr/acl/dto/FundsCaptureRequest.java`

```java
@Getter
@Builder
public class FundsCaptureRequest {

    private Long walletId;
    private Long storeId;
    private Long customerId;
    private Long amount;
    private List<ItemSnapshot> items;

    /**
     * 메뉴 항목 스냅샷 (결제 시점 데이터)
     */
    @Getter
    @Builder
    public static class ItemSnapshot {
        private Long menuId;
        private String menuName;
        private Long unitPrice;
        private Integer quantity;
    }
}
```

**파일:** `services/qr-service/src/main/java/com/ssafy/keeping/qr/acl/dto/FundsResponse.java`

```java
@Getter
@Setter
public class FundsResponse {

    private boolean sufficient;      // 잔액 충분 여부
    private boolean policyOk;        // 정책 준수 여부 (한도 등)
    private Long transactionId;      // 생성된 거래 ID
    private String errorCode;        // 오류 코드 (PAYMENT_IN_PROGRESS 등)

    public static FundsResponse insufficient() {
        FundsResponse response = new FundsResponse();
        response.setSufficient(false);
        return response;
    }

    public static FundsResponse ok(Long transactionId) {
        FundsResponse response = new FundsResponse();
        response.setSufficient(true);
        response.setPolicyOk(true);
        response.setTransactionId(transactionId);
        return response;
    }
}
```

#### 2.5.3 도메인 서비스 - FundsService (ACL 어댑터)

**파일:** `services/qr-service/src/main/java/com/ssafy/keeping/qr/domain/intent/service/FundsService.java`

```java
@Service
@RequiredArgsConstructor
public class FundsService {

    private final WalletClient walletClient;  // ACL 의존

    /**
     * 자금 캡처 - 도메인 모델을 ACL DTO로 변환하여 호출
     */
    public FundsResult capture(PaymentIntent intent, List<PaymentIntentItem> items) {

        // 1. 도메인 모델 → ACL DTO 변환
        List<FundsCaptureRequest.ItemSnapshot> snapshots = items.stream()
            .map(item -> FundsCaptureRequest.ItemSnapshot.builder()
                .menuId(item.getMenuId())
                .menuName(item.getMenuNameSnap())
                .unitPrice(item.getUnitPriceSnap())
                .quantity(item.getQuantity())
                .build())
            .collect(Collectors.toList());

        FundsCaptureRequest request = FundsCaptureRequest.builder()
            .walletId(intent.getWalletId())
            .storeId(intent.getStoreId())
            .customerId(intent.getCustomerId())
            .amount(intent.getAmount())
            .items(snapshots)
            .build();

        // 2. ACL을 통해 외부 서비스 호출
        FundsResponse response = walletClient.capture(request);

        // 3. ACL DTO → 도메인 모델 변환
        return FundsResult.builder()
            .sufficient(response.isSufficient())
            .policyOk(response.isPolicyOk())
            .transactionId(response.getTransactionId())
            .errorCode(response.getErrorCode())
            .build();
    }

    /**
     * 잔액 조회
     */
    public BigDecimal getBalance(Long walletId, Long storeId) {
        return walletClient.getBalance(walletId, storeId);
    }
}
```

#### 2.5.4 모놀리스 Internal API

**파일:** `src/main/java/com/ssafy/keeping/domain/internal/controller/InternalWalletController.java`

```java
@RestController
@RequestMapping("/internal/wallets")
@RequiredArgsConstructor
public class InternalWalletController {

    private static final String INTERNAL_AUTH_TOKEN = "internal-service-token-12345";

    private final InternalWalletService internalWalletService;

    /**
     * 잔액 조회 (매장별)
     */
    @GetMapping("/{walletId}/stores/{storeId}/balance")
    public ResponseEntity<WalletBalanceResponse> getBalance(
            @PathVariable Long walletId,
            @PathVariable Long storeId,
            @RequestHeader("X-Internal-Auth") String authToken) {

        validateInternalAuth(authToken);

        BigDecimal balance = internalWalletService.getBalance(walletId, storeId);
        return ResponseEntity.ok(new WalletBalanceResponse(balance));
    }

    /**
     * 자금 캡처 (결제)
     */
    @PostMapping("/{walletId}/stores/{storeId}/capture")
    public ResponseEntity<FundsResponse> capture(
            @PathVariable Long walletId,
            @PathVariable Long storeId,
            @RequestBody FundsCaptureRequest request,
            @RequestHeader("X-Internal-Auth") String authToken) {

        validateInternalAuth(authToken);

        FundsResponse response = internalWalletService.capture(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 자금 복원 (취소)
     */
    @PostMapping("/{walletId}/stores/{storeId}/restore")
    public ResponseEntity<Void> restore(
            @PathVariable Long walletId,
            @PathVariable Long storeId,
            @RequestBody Map<String, Long> body,
            @RequestHeader("X-Internal-Auth") String authToken) {

        validateInternalAuth(authToken);

        internalWalletService.restore(walletId, storeId, body.get("amount"));
        return ResponseEntity.ok().build();
    }

    private void validateInternalAuth(String authToken) {
        if (!INTERNAL_AUTH_TOKEN.equals(authToken)) {
            throw new IllegalArgumentException("Internal API 인증 실패");
        }
    }
}
```

### 2.6 ACL 데이터 흐름

```
┌────────────┐      ┌────────────────┐      ┌───────────────┐      ┌────────────┐
│  손님 앱   │      │   QR Service   │      │  ACL Layer    │      │  Monolith  │
│            │      │                │      │               │      │            │
└─────┬──────┘      └───────┬────────┘      └───────┬───────┘      └─────┬──────┘
      │                     │                       │                     │
      │ POST /payments      │                       │                     │
      │ /{id}/approve       │                       │                     │
      │────────────────────>│                       │                     │
      │                     │                       │                     │
      │                     │ 1. PaymentIntent 조회  │                     │
      │                     │    (QR Service DB)    │                     │
      │                     │                       │                     │
      │                     │ 2. FundsService       │                     │
      │                     │    .capture()         │                     │
      │                     │──────────────────────>│                     │
      │                     │                       │                     │
      │                     │   ┌───────────────────┴───────────────────┐ │
      │                     │   │ 도메인 모델 → ACL DTO 변환             │ │
      │                     │   │ PaymentIntentItem → ItemSnapshot      │ │
      │                     │   └───────────────────┬───────────────────┘ │
      │                     │                       │                     │
      │                     │                       │ 3. WalletClient     │
      │                     │                       │    .capture()       │
      │                     │                       │────────────────────>│
      │                     │                       │                     │
      │                     │                       │ POST /internal/     │
      │                     │                       │ wallets/.../capture │
      │                     │                       │ X-Internal-Auth     │
      │                     │                       │                     │
      │                     │                       │                     │ 4. 비관적 락
      │                     │                       │                     │    잔액 차감
      │                     │                       │                     │    Transaction 생성
      │                     │                       │                     │
      │                     │                       │    FundsResponse    │
      │                     │                       │<────────────────────│
      │                     │                       │                     │
      │                     │   ┌───────────────────┴───────────────────┐ │
      │                     │   │ ACL DTO → 도메인 모델 변환             │ │
      │                     │   │ FundsResponse → FundsResult           │ │
      │                     │   └───────────────────┬───────────────────┘ │
      │                     │                       │                     │
      │                     │      FundsResult      │                     │
      │                     │<──────────────────────│                     │
      │                     │                       │                     │
      │                     │ 5. PaymentIntent      │                     │
      │                     │    상태 업데이트       │                     │
      │                     │    PENDING → APPROVED │                     │
      │                     │                       │                     │
      │   결제 완료 응답     │                       │                     │
      │<────────────────────│                       │                     │
      │                     │                       │                     │
```

### 2.7 ACL 클라이언트 목록

| Client | 역할 | 엔드포인트 | 메서드 |
|--------|------|-----------|--------|
| **WalletClient** | 지갑/자금 관리 | `/internal/wallets/*` | getBalance(), capture(), restore() |
| **CustomerClient** | 고객 정보/PIN | `/internal/customers/*` | getCustomer(), verifyPin() |
| **MenuClient** | 메뉴 조회 | `/internal/menus/*` | getMenus(List<Long>) |
| **StoreClient** | 매장 정보 | `/internal/stores/*` | getStore(Long) |
| **NotificationClient** | 알림 전송 | `/internal/notifications/*` | sendToCustomer(), sendToOwner() |

---

## 3. 물리적 서버 분리

### 3.1 개요

```
EC2-A (QR Service) / EC2-B (모놀리스)
=> CPU/메모리 완전 격리 → 성능 개선
```

서비스를 별도의 서버(EC2 인스턴스)에 배포하여 리소스를 완전히 격리합니다.

### 3.2 특징

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                          물리적 서버 분리 구조                                    │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│   Client (Mobile App / Web)                                                      │
│            │                                                                     │
│            ▼                                                                     │
│   ┌─────────────────────────────────────────────────────────────────────────┐   │
│   │                     Load Balancer / API Gateway                          │   │
│   │                     (ALB or Nginx on EC2)                                │   │
│   └─────────────────────────────────────────────────────────────────────────┘   │
│            │                                      │                              │
│            │ /api/qr, /cpqr, /payments           │ /api/*                       │
│            ▼                                      ▼                              │
│   ┌─────────────────────────┐          ┌─────────────────────────┐              │
│   │       EC2-A             │          │       EC2-B             │              │
│   │     (QR Service)        │          │     (Monolith)          │              │
│   │                         │          │                         │              │
│   │  ┌─────────────────┐   │          │  ┌─────────────────┐   │              │
│   │  │  QR Service     │   │   HTTP   │  │   Spring Boot   │   │              │
│   │  │  Spring Boot    │───┼─────────>│  │   Application   │   │              │
│   │  │  (Port 8082)    │   │ Internal │  │   (Port 8080)   │   │              │
│   │  └─────────────────┘   │   API    │  └─────────────────┘   │              │
│   │                         │          │                         │              │
│   │  Resources:             │          │  Resources:             │              │
│   │  • vCPU: 2             │          │  • vCPU: 4             │              │
│   │  • RAM: 4GB            │          │  • RAM: 8GB            │              │
│   │  • Instance: t3.medium │          │  • Instance: t3.xlarge │              │
│   │                         │          │                         │              │
│   └─────────────────────────┘          └─────────────────────────┘              │
│                                                   │                              │
│                                                   ▼                              │
│                              ┌─────────────────────────────────────────────┐    │
│                              │              Shared Resources               │    │
│                              │                                              │    │
│                              │  ┌─────────────┐    ┌─────────────┐         │    │
│                              │  │  Amazon RDS │    │ ElastiCache │         │    │
│                              │  │  (MySQL)    │    │  (Redis)    │         │    │
│                              │  │             │    │             │         │    │
│                              │  │ • keeping   │    │ • QR Token  │         │    │
│                              │  │ • payment_  │    │ • Session   │         │    │
│                              │  │   service   │    │ • Cache     │         │    │
│                              │  └─────────────┘    └─────────────┘         │    │
│                              │                                              │    │
│                              └─────────────────────────────────────────────┘    │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### 3.3 장점

```
┌─────────────────────────────────────────────────────────────────┐
│                         장점                                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. CPU/메모리 완전 격리                                         │
│     ┌─────────────────────────────────────────────────────────┐ │
│     │ Before (단일 서버)                                       │ │
│     │   ┌────────────────────────────────────────────────────┐│ │
│     │   │ CPU: 100%  ████████████████████████████████████████││ │
│     │   │            [Monolith 80%] [QR 20% - 리소스 경쟁!]   ││ │
│     │   └────────────────────────────────────────────────────┘│ │
│     │                                                          │ │
│     │ After (분리 서버)                                        │ │
│     │   EC2-A: ████████░░  (QR 전용 - 여유 있음)              │ │
│     │   EC2-B: ██████████  (Monolith 전용 - 독립 운영)         │ │
│     └─────────────────────────────────────────────────────────┘ │
│                                                                  │
│  2. 독립적 스케일링                                              │
│     - QR 결제 폭증 시: EC2-A만 Auto Scaling                     │
│     - 일반 API 부하 시: EC2-B만 Auto Scaling                    │
│     - 비용 효율적 리소스 할당                                   │
│                                                                  │
│  3. 장애 격리 (Fault Isolation)                                 │
│     - QR Service OOM → Monolith 영향 없음                       │
│     - Monolith 재시작 → QR Service 정상 동작                    │
│     - 부분 장애 시에도 핵심 기능 유지                           │
│                                                                  │
│  4. 독립적 배포                                                  │
│     - QR Service 업데이트: EC2-A만 배포                         │
│     - Monolith 업데이트: EC2-B만 배포                           │
│     - 롤백도 독립적으로 수행                                    │
│                                                                  │
│  5. 성능 모니터링 명확                                           │
│     - 서비스별 CPU/메모리 사용량 분리 측정                       │
│     - 병목 지점 파악 용이                                        │
│     - CloudWatch 메트릭 서비스별 분리                            │
│                                                                  │
│  6. 보안 격리                                                    │
│     - 서비스별 Security Group 적용                               │
│     - 네트워크 정책 세분화                                       │
│     - Internal API만 Private Subnet 통신                        │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 3.4 단점

```
┌─────────────────────────────────────────────────────────────────┐
│                         단점                                     │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. 인프라 비용 증가                                             │
│     - EC2 인스턴스 추가 비용                                     │
│     - 네트워크 트래픽 비용 (서비스 간 통신)                       │
│     - 관리 포인트 증가                                           │
│                                                                  │
│  2. 네트워크 레이턴시                                            │
│     - 서비스 간 HTTP 호출 오버헤드                               │
│     - 동일 서버 내 호출 대비 ~1-5ms 추가                         │
│     - 네트워크 장애 시 통신 실패 가능                            │
│                                                                  │
│  3. 운영 복잡도                                                  │
│     - 여러 서버 관리 필요                                        │
│     - 배포 파이프라인 복잡화                                     │
│     - 로그 수집/분석 분산                                        │
│                                                                  │
│  4. 데이터 일관성 관리                                           │
│     - 분산 트랜잭션 필요                                         │
│     - 최종 일관성 패턴 적용                                      │
│     - 모니터링/알림 체계 복잡화                                  │
│                                                                  │
│  5. 서비스 디스커버리                                            │
│     - 서비스 위치 관리 필요                                      │
│     - 로드밸런서/DNS 설정 필요                                   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### 3.5 분리 예시 (프로젝트 설정)

#### 3.5.1 Docker Compose MSA 구성

**파일:** `docker-compose.msa.yml`

```yaml
version: '3.8'

services:
  # API Gateway (Nginx) - 모든 요청의 진입점
  nginx:
    image: nginx:alpine
    container_name: keeping-nginx
    ports:
      - "80:80"                    # 외부 접근 포트
    volumes:
      - ./gateway/nginx.conf:/etc/nginx/nginx.conf:ro
    depends_on:
      - monolith
      - qr-service
    networks:
      - keeping-network

  # 모놀리스 (EC2-B에 해당)
  monolith:
    image: ${DOCKER_USERNAME}/keeping-backend:latest
    container_name: keeping-monolith
    ports:
      - "8080:8080"                # 테스트용 (프로덕션에서는 내부만)
    environment:
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/keeping
      SPRING_DATA_REDIS_HOST: redis
      JAVA_OPTS: "-Xms512m -Xmx1536m"    # 메모리 할당
    networks:
      - keeping-network
    deploy:
      resources:
        limits:
          cpus: '2.0'              # CPU 제한
          memory: 2G               # 메모리 제한

  # QR Service (EC2-A에 해당)
  qr-service:
    build:
      context: ./services/qr-service
      dockerfile: Dockerfile
    container_name: keeping-qr-service
    expose:
      - "8082"                     # 내부 포트만 (nginx 통해 접근)
    environment:
      DB_URL: jdbc:mysql://mysql:3306/payment_service
      REDIS_HOST: redis
      MONOLITH_URL: http://monolith:8080    # 내부 통신
      INTERNAL_AUTH_TOKEN: internal-service-token-12345
    networks:
      - keeping-network
    deploy:
      resources:
        limits:
          cpus: '1.0'              # CPU 제한 (별도)
          memory: 1G               # 메모리 제한 (별도)

  # 공유 리소스
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_DATABASE: keeping
    volumes:
      - mysql_data:/var/lib/mysql
      - ./mysql/init:/docker-entrypoint-initdb.d:ro   # 스키마 초기화
    networks:
      - keeping-network

  redis:
    image: redis:7-alpine
    networks:
      - keeping-network

networks:
  keeping-network:
    driver: bridge

volumes:
  mysql_data:
```

#### 3.5.2 Nginx Gateway 라우팅

**파일:** `gateway/nginx.conf`

```nginx
events {
    worker_connections 1024;
}

http {
    # 백엔드 서버 정의
    upstream monolith {
        server monolith:8080;      # EC2-B (또는 Docker 컨테이너)
    }

    upstream qr-service {
        server qr-service:8082;    # EC2-A (또는 Docker 컨테이너)
    }

    server {
        listen 80;

        # ========================================
        # QR Service 라우팅 (EC2-A로 전달)
        # ========================================

        # QR 토큰 API
        location /api/qr {
            proxy_pass http://qr-service;
            proxy_set_header Host $host;
            proxy_set_header Authorization $http_authorization;
            proxy_connect_timeout 10s;
            proxy_read_timeout 30s;
        }

        # 결제 의도 생성 (점주용)
        location ~ ^/cpqr/([^/]+)/initiate$ {
            proxy_pass http://qr-service;
            proxy_set_header Idempotency-Key $http_idempotency_key;
        }

        # 결제 승인 (고객용)
        location ~ ^/payments/([^/]+)/approve$ {
            proxy_pass http://qr-service;
            proxy_set_header Idempotency-Key $http_idempotency_key;
        }

        # 결제 의도 조회
        location ~ ^/api/payments/intent/ {
            proxy_pass http://qr-service;
        }

        # ========================================
        # Monolith 라우팅 (EC2-B로 전달)
        # ========================================

        # 기타 모든 API
        location /api {
            proxy_pass http://monolith;
            # 인증 검증 후 전달
            auth_request /internal/auth/verify;
        }

        # 기본 라우팅
        location / {
            proxy_pass http://monolith;
        }
    }
}
```

#### 3.5.3 AWS 배포 아키텍처 예시

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              AWS Architecture                                    │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│                              ┌─────────────────┐                                 │
│                              │   Route 53      │                                 │
│                              │  (DNS)          │                                 │
│                              └────────┬────────┘                                 │
│                                       │                                          │
│                              ┌────────▼────────┐                                 │
│                              │      ALB        │                                 │
│                              │ (Application    │                                 │
│                              │  Load Balancer) │                                 │
│                              └────────┬────────┘                                 │
│                                       │                                          │
│              ┌────────────────────────┼────────────────────────┐                │
│              │                        │                        │                │
│    ┌─────────▼─────────┐    ┌─────────▼─────────┐    ┌─────────▼─────────┐     │
│    │  Target Group 1   │    │  Target Group 2   │    │  Target Group 3   │     │
│    │  /api/qr/*        │    │  /cpqr/*, /pay*   │    │  /api/* (other)   │     │
│    └─────────┬─────────┘    └─────────┬─────────┘    └─────────┬─────────┘     │
│              │                        │                        │                │
│              ▼                        ▼                        ▼                │
│    ┌───────────────────────────────────────────┐    ┌───────────────────┐      │
│    │              EC2-A (QR Service)           │    │    EC2-B          │      │
│    │                                           │    │   (Monolith)      │      │
│    │  ┌─────────────────────────────────────┐ │    │                   │      │
│    │  │ Docker: qr-service                  │ │    │  ┌─────────────┐ │      │
│    │  │ - Spring Boot (8082)                │ │    │  │ Docker:     │ │      │
│    │  │ - JVM: -Xmx1g                       │ │    │  │ monolith    │ │      │
│    │  └─────────────────────────────────────┘ │    │  │ (8080)      │ │      │
│    │                                           │    │  │ -Xmx2g      │ │      │
│    │  Instance: t3.medium                      │    │  └─────────────┘ │      │
│    │  vCPU: 2, RAM: 4GB                        │    │                   │      │
│    │                                           │    │  t3.xlarge       │      │
│    │  Auto Scaling Group:                      │    │  vCPU: 4         │      │
│    │  - Min: 1, Max: 4                         │    │  RAM: 8GB        │      │
│    │  - Scale on CPU > 70%                     │    │                   │      │
│    └───────────────────────────────────────────┘    └───────────────────┘      │
│                                                                                  │
│    ┌─────────────────────────────────────────────────────────────────────┐     │
│    │                        Private Subnet                                │     │
│    │                                                                      │     │
│    │   ┌──────────────────┐              ┌──────────────────┐            │     │
│    │   │   Amazon RDS     │              │   ElastiCache    │            │     │
│    │   │   (MySQL 8.0)    │              │   (Redis 7)      │            │     │
│    │   │                  │              │                  │            │     │
│    │   │ Multi-AZ         │              │ Cluster Mode     │            │     │
│    │   │ • keeping        │              │                  │            │     │
│    │   │ • payment_service│              │                  │            │     │
│    │   └──────────────────┘              └──────────────────┘            │     │
│    │                                                                      │     │
│    └─────────────────────────────────────────────────────────────────────┘     │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### 3.6 리소스 할당 비교

| 항목 | Before (단일 서버) | After (분리 서버) |
|------|-------------------|-------------------|
| **총 vCPU** | 4 (공유) | 6 (2+4, 격리) |
| **총 메모리** | 8GB (공유) | 12GB (4+8, 격리) |
| **QR Service 가용** | 최대 ~25% (경쟁) | 100% (전용) |
| **Monolith 가용** | 최대 ~75% (경쟁) | 100% (전용) |
| **장애 영향** | 전체 서비스 | 해당 서비스만 |
| **배포 영향** | 전체 다운타임 | 해당 서비스만 |

### 3.7 성능 개선 효과

```
┌─────────────────────────────────────────────────────────────────┐
│                     성능 개선 시나리오                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  시나리오: QR 결제 트래픽 급증 (이벤트 기간)                      │
│                                                                  │
│  Before (단일 서버):                                             │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │ CPU 100% ████████████████████████████████████████████████████││
│  │          [Monolith 60%][QR Service 40% - 서로 리소스 경쟁]   ││
│  │                                                              ││
│  │ 결과:                                                        ││
│  │ • QR 응답 지연: 500ms → 2000ms                              ││
│  │ • Monolith API 영향: 정상 → 지연                             ││
│  │ • 전체 서비스 품질 저하                                       ││
│  └─────────────────────────────────────────────────────────────┘│
│                                                                  │
│  After (분리 서버):                                              │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │ EC2-A (QR): CPU 95% █████████████████████████████████████░░░ ││
│  │             → Auto Scaling 발동 → 인스턴스 추가              ││
│  │                                                              ││
│  │ EC2-B (Mono): CPU 40% ████████████████░░░░░░░░░░░░░░░░░░░░░░││
│  │               → 영향 없음, 정상 운영                         ││
│  │                                                              ││
│  │ 결과:                                                        ││
│  │ • QR 응답: 자동 확장으로 정상 유지                           ││
│  │ • Monolith: 완전히 격리, 영향 없음                           ││
│  │ • 서비스 품질 유지                                           ││
│  └─────────────────────────────────────────────────────────────┘│
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## 4. 종합 아키텍처 다이어그램

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                        MSA Migration Complete Architecture                       │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│                              ┌─────────────────┐                                 │
│                              │   Client Apps   │                                 │
│                              │  (Mobile/Web)   │                                 │
│                              └────────┬────────┘                                 │
│                                       │                                          │
│                              ┌────────▼────────┐                                 │
│                              │   API Gateway   │                                 │
│                              │    (Nginx)      │                                 │
│                              └────────┬────────┘                                 │
│                    ┌──────────────────┼──────────────────┐                      │
│                    │                  │                  │                      │
│                    ▼                  │                  ▼                      │
│   ┌────────────────────────────┐     │     ┌────────────────────────────┐      │
│   │         EC2-A              │     │     │         EC2-B              │      │
│   │      (QR Service)          │     │     │       (Monolith)           │      │
│   │                            │     │     │                            │      │
│   │  ┌──────────────────────┐ │     │     │  ┌──────────────────────┐ │      │
│   │  │    Domain Layer      │ │     │     │  │    Domain Layer      │ │      │
│   │  │  ┌────────────────┐  │ │     │     │  │  ┌────────────────┐  │ │      │
│   │  │  │ PaymentIntent  │  │ │     │     │  │  │    Wallet      │  │ │      │
│   │  │  │ (ID 참조만)    │  │ │     │     │  │  │  (@ManyToOne)  │  │ │      │
│   │  │  │ • customerId   │  │ │     │     │  │  └────────────────┘  │ │      │
│   │  │  │ • walletId     │  │ │     │     │  │  ┌────────────────┐  │ │      │
│   │  │  │ • storeId      │  │ │     │     │  │  │  Transaction   │  │ │      │
│   │  │  └────────────────┘  │ │     │     │  │  │  (@ManyToOne)  │  │ │      │
│   │  └──────────────────────┘ │     │     │  │  └────────────────┘  │ │      │
│   │            │              │     │     │  │  ┌────────────────┐  │ │      │
│   │            ▼              │     │     │  │  │   Customer     │  │ │      │
│   │  ┌──────────────────────┐ │     │     │  │  └────────────────┘  │ │      │
│   │  │     ACL Layer        │ │     │     │  └──────────────────────┘ │      │
│   │  │  ┌────────────────┐  │ │     │     │            │              │      │
│   │  │  │ WalletClient   │──┼─┼─────┼─────┼────────────┘              │      │
│   │  │  │ CustomerClient │  │ │ HTTP│     │  ┌──────────────────────┐ │      │
│   │  │  │ MenuClient     │──┼─┼─────┼─────┼─>│   Internal API       │ │      │
│   │  │  └────────────────┘  │ │     │     │  │  /internal/wallets   │ │      │
│   │  └──────────────────────┘ │     │     │  │  /internal/customers │ │      │
│   │                            │     │     │  └──────────────────────┘ │      │
│   │  DB: payment_service       │     │     │  DB: keeping             │      │
│   └────────────────────────────┘     │     └────────────────────────────┘      │
│                                       │                                          │
│                              ┌────────▼────────┐                                 │
│                              │  Shared Infra   │                                 │
│                              │  ┌───────────┐  │                                 │
│                              │  │   MySQL   │  │                                 │
│                              │  │  (RDS)    │  │                                 │
│                              │  └───────────┘  │                                 │
│                              │  ┌───────────┐  │                                 │
│                              │  │   Redis   │  │                                 │
│                              │  │(Elasticache)│ │                                │
│                              │  └───────────┘  │                                 │
│                              └─────────────────┘                                 │
│                                                                                  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

---

## 5. 마이그레이션 체크리스트

### 5.1 연관관계 분리

- [x] @ManyToOne → ID 참조 변환 (PaymentIntent)
- [x] 스냅샷 필드 추가 (menuNameSnap, unitPriceSnap)
- [x] 낙관적 락 적용 (@Version)
- [x] 별도 DB 스키마 (payment_service)

### 5.2 ACL 패턴 적용

- [x] WalletClient 구현
- [x] CustomerClient 구현
- [x] MenuClient 구현
- [x] StoreClient 구현
- [x] NotificationClient 구현
- [x] ACL DTO 정의 (FundsCaptureRequest, FundsResponse 등)
- [x] Internal API 엔드포인트 (/internal/*)
- [x] 서비스 간 인증 (X-Internal-Auth)

### 5.3 물리적 분리

- [x] Docker Compose MSA 구성
- [x] Nginx Gateway 라우팅
- [x] 리소스 제한 설정 (CPU/메모리)
- [ ] AWS Auto Scaling 설정
- [ ] CloudWatch 모니터링
- [ ] CI/CD 파이프라인 분리

---

## 6. 참고 파일 경로

| 구분 | 파일 경로 |
|------|----------|
| **QR Service 엔티티** | `services/qr-service/src/main/java/com/ssafy/keeping/qr/domain/intent/model/` |
| **ACL 클라이언트** | `services/qr-service/src/main/java/com/ssafy/keeping/qr/acl/` |
| **ACL DTO** | `services/qr-service/src/main/java/com/ssafy/keeping/qr/acl/dto/` |
| **모놀리스 Internal API** | `src/main/java/com/ssafy/keeping/domain/internal/controller/` |
| **Docker Compose MSA** | `docker-compose.msa.yml` |
| **Nginx 설정** | `gateway/nginx.conf` |

---

## 7. 결론

이 프로젝트는 세 가지 핵심 패턴을 통해 MSA 마이그레이션을 구현했습니다:

1. **연관관계 분리**: 서비스 간 데이터 독립성 확보
2. **ACL 패턴**: 변경 격리 및 느슨한 결합 달성
3. **물리적 분리**: 리소스 격리 및 독립적 확장성 확보

이를 통해 QR 결제 기능을 독립적으로 개발, 배포, 운영할 수 있는 마이크로서비스 아키텍처를 구축했습니다.
