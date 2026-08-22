# Payment Service MSA 분리 작업 문서

## 개요

| 항목 | 내용 |
|------|------|
| **목표** | 모놀리스의 Payment 도메인을 별도 마이크로서비스로 분리 |
| **서비스 포트** | 8082 |
| **패턴** | ACL (Anti-Corruption Layer) 패턴 |
| **데이터베이스** | payment_service (별도 스키마) |

---

## 1. 프로젝트 구조

### 1.1 Payment Service 디렉토리 구조

```
services/payment-service/
├── build.gradle
├── settings.gradle
├── Dockerfile
├── src/main/java/com/ssafy/keeping/payment/
│   ├── PaymentServiceApplication.java
│   │
│   ├── config/
│   │   ├── RestTemplateConfig.java      # HTTP 클라이언트 설정 (5초 타임아웃)
│   │   ├── JpaConfig.java               # JPA Auditing, Clock Bean
│   │   └── ObjectMapperConfig.java      # Canonical ObjectMapper (멱등성용)
│   │
│   ├── common/
│   │   ├── response/
│   │   │   └── ApiResponse.java         # 공통 응답 래퍼
│   │   ├── exception/
│   │   │   ├── ErrorCode.java           # 에러 코드 정의
│   │   │   ├── CustomException.java     # 커스텀 예외
│   │   │   └── GlobalExceptionHandler.java
│   │   └── IdUtil.java                  # UUID v7 생성 유틸
│   │
│   ├── acl/                             # Anti-Corruption Layer
│   │   ├── WalletClient.java            # 잔액 조회/차감/복원
│   │   ├── StoreClient.java             # 매장 정보 조회
│   │   ├── MenuClient.java              # 메뉴 일괄 조회
│   │   ├── CustomerClient.java          # 고객 정보/PIN 검증
│   │   ├── NotificationClient.java      # 알림 발송
│   │   ├── QrPaymentClient.java         # QR 토큰 조회/소비
│   │   └── dto/
│   │       ├── WalletBalanceResponse.java
│   │       ├── FundsCaptureRequest.java
│   │       ├── FundsResponse.java
│   │       ├── StoreResponse.java
│   │       ├── MenuResponse.java
│   │       ├── CustomerResponse.java
│   │       ├── PinVerifyRequest.java
│   │       ├── PinVerifyResponse.java
│   │       ├── NotificationRequest.java
│   │       └── QrTokenResponse.java
│   │
│   ├── domain/
│   │   ├── intent/                      # 결제 의도 도메인
│   │   │   ├── model/
│   │   │   │   ├── PaymentIntent.java
│   │   │   │   └── PaymentIntentItem.java
│   │   │   ├── repository/
│   │   │   │   ├── PaymentIntentRepository.java
│   │   │   │   └── PaymentIntentItemRepository.java
│   │   │   ├── constant/
│   │   │   │   └── PaymentStatus.java
│   │   │   ├── dto/
│   │   │   │   ├── PaymentInitiateRequest.java
│   │   │   │   ├── PaymentInitiateItemDto.java
│   │   │   │   ├── ApproveRequest.java
│   │   │   │   ├── PaymentIntentDetailResponse.java
│   │   │   │   └── PaymentIntentItemView.java
│   │   │   ├── canonical/
│   │   │   │   ├── CanonicalInitiate.java
│   │   │   │   └── CanonicalApprove.java
│   │   │   ├── service/
│   │   │   │   ├── PaymentIntentService.java
│   │   │   │   └── FundsService.java
│   │   │   └── controller/
│   │   │       ├── PaymentIntentController.java
│   │   │       └── PaymentApprovalController.java
│   │   │
│   │   └── idempotency/                 # 멱등성 처리
│   │       ├── model/
│   │       │   ├── IdempotencyKey.java
│   │       │   └── IdempotentResult.java
│   │       ├── constant/
│   │       │   ├── IdemActorType.java
│   │       │   └── IdemStatus.java
│   │       ├── dto/
│   │       │   └── IdemBegin.java
│   │       ├── repository/
│   │       │   └── IdempotencyKeyRepository.java
│   │       └── service/
│   │           └── IdempotencyService.java
│   │
│   ├── gateway/                         # 결제 게이트웨이 (Strategy Pattern)
│   │   ├── PaymentGateway.java          # 인터페이스
│   │   ├── PaymentGatewayFactory.java   # 팩토리
│   │   ├── PaymentProvider.java         # enum (TOSS, etc.)
│   │   ├── dto/
│   │   │   ├── PaymentRequest.java
│   │   │   ├── PaymentResult.java
│   │   │   ├── CancelRequest.java
│   │   │   └── CancelResult.java
│   │   └── impl/
│   │       └── TossPaymentGateway.java
│   │
│   └── toss/                            # 토스페이먼츠 API
│       ├── TossPaymentClient.java
│       ├── config/
│       │   └── TossPaymentConfig.java
│       └── dto/
│           ├── TossPaymentConfirmRequest.java
│           ├── TossPaymentConfirmResponse.java
│           ├── TossCancelRequest.java
│           └── TossCancelResponse.java
│
└── src/main/resources/
    └── application.yml
```

### 1.2 모놀리스 Internal API 구조

```
src/main/java/com/ssafy/keeping/domain/internal/
├── controller/
│   ├── InternalWalletController.java
│   ├── InternalStoreController.java
│   ├── InternalMenuController.java
│   ├── InternalCustomerController.java
│   └── InternalNotificationController.java
├── service/
│   └── InternalWalletService.java
└── dto/
    ├── WalletBalanceResponse.java
    ├── FundsCaptureRequest.java
    ├── FundsResponse.java
    ├── StoreResponse.java
    ├── MenuResponse.java
    ├── BatchMenuRequest.java
    ├── CustomerResponse.java
    ├── PinVerifyRequest.java
    ├── PinVerifyResponse.java
    ├── NotificationRequest.java
    └── QrTokenResponse.java
```

---

## 2. 구현 상세

### 2.1 Payment Service 기본 설정

#### build.gradle

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.2.0'
    id 'io.spring.dependency-management' version '1.1.4'
}

group = 'com.ssafy.keeping'
version = '0.0.1-SNAPSHOT'

java {
    sourceCompatibility = '17'
}

dependencies {
    // Spring Boot 기본
    implementation 'org.springframework.boot:spring-boot-starter-web'

    // JPA + MySQL
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    runtimeOnly 'com.mysql:mysql-connector-j'

    // Validation
    implementation 'org.springframework.boot:spring-boot-starter-validation'

    // JWT
    implementation 'io.jsonwebtoken:jjwt-api:0.12.3'
    runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.12.3'
    runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.12.3'

    // UUID v7
    implementation 'com.github.f4b6a3:uuid-creator:5.3.3'

    // Lombok
    compileOnly 'org.projectlombok:lombok'
    annotationProcessor 'org.projectlombok:lombok'

    // Actuator
    implementation 'org.springframework.boot:spring-boot-starter-actuator'

    // 테스트
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
}
```

#### application.yml

```yaml
server:
  port: 8082

spring:
  application:
    name: payment-service
  datasource:
    url: ${DB_URL:jdbc:mysql://localhost:3306/payment_service?useSSL=false&serverTimezone=Asia/Seoul&characterEncoding=UTF-8&allowPublicKeyRetrieval=true}
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD:1234}
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: false
    properties:
      hibernate:
        format_sql: true
        dialect: org.hibernate.dialect.MySQLDialect

jwt:
  secret: ${JWT_SECRET:your-secret-key}

monolith:
  url: ${MONOLITH_URL:http://localhost:8080}

qr-payment:
  url: ${QR_PAYMENT_URL:http://localhost:8081}

payment:
  toss:
    secret-key: ${TOSS_SECRET_KEY:test_sk_xxx}
    base-url: https://api.tosspayments.com

internal:
  auth-token: ${INTERNAL_AUTH_TOKEN:internal-service-token-12345}

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: always
```

#### Dockerfile

```dockerfile
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY build/libs/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
```

---

### 2.2 ACL (Anti-Corruption Layer) 클라이언트

#### RestTemplateConfig.java

```java
@Configuration
public class RestTemplateConfig {

    @Value("${internal.auth-token}")
    private String internalAuthToken;

    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);  // 5초
        factory.setReadTimeout(5000);     // 5초

        RestTemplate restTemplate = new RestTemplate(factory);

        // Internal API 인증 헤더 자동 추가
        restTemplate.getInterceptors().add((request, body, execution) -> {
            request.getHeaders().add("X-Internal-Auth", internalAuthToken);
            return execution.execute(request, body);
        });

        return restTemplate;
    }
}
```

#### WalletClient.java (예시)

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class WalletClient {

    private final RestTemplate restTemplate;

    @Value("${monolith.url}")
    private String monolithUrl;

    public WalletBalanceResponse getBalance(Long walletId, Long storeId) {
        String url = monolithUrl + "/internal/wallets/" + walletId + "/stores/" + storeId + "/balance";
        try {
            return restTemplate.getForObject(url, WalletBalanceResponse.class);
        } catch (Exception e) {
            log.error("잔액 조회 실패: walletId={}, storeId={}", walletId, storeId, e);
            throw new CustomException(ErrorCode.INTERNAL_API_ERROR);
        }
    }

    public FundsResponse capture(FundsCaptureRequest request) {
        String url = monolithUrl + "/internal/wallets/" + request.getWalletId()
                   + "/stores/" + request.getStoreId() + "/capture";
        try {
            return restTemplate.postForObject(url, request, FundsResponse.class);
        } catch (Exception e) {
            log.error("자금 캡처 실패: {}", request, e);
            throw new CustomException(ErrorCode.INTERNAL_API_ERROR);
        }
    }

    public void restore(Long walletId, Long storeId, Long amount) {
        String url = monolithUrl + "/internal/wallets/" + walletId
                   + "/stores/" + storeId + "/restore";
        try {
            restTemplate.postForObject(url, Map.of("amount", amount), Void.class);
        } catch (Exception e) {
            log.error("잔액 복원 실패: walletId={}, storeId={}, amount={}",
                     walletId, storeId, amount, e);
            throw new CustomException(ErrorCode.INTERNAL_API_ERROR);
        }
    }
}
```

---

### 2.3 모놀리스 Internal API

#### InternalWalletController.java

```java
@Slf4j
@RestController
@RequestMapping("/internal/wallets")
@RequiredArgsConstructor
public class InternalWalletController {

    private final InternalWalletService internalWalletService;
    private static final String INTERNAL_AUTH_TOKEN = "internal-service-token-12345";

    @GetMapping("/{walletId}/stores/{storeId}/balance")
    public ResponseEntity<WalletBalanceResponse> getBalance(
            @PathVariable Long walletId,
            @PathVariable Long storeId,
            @RequestHeader(value = "X-Internal-Auth", required = false) String authToken
    ) {
        validateInternalAuth(authToken);
        WalletBalanceResponse response = internalWalletService.getBalance(walletId, storeId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{walletId}/stores/{storeId}/capture")
    public ResponseEntity<FundsResponse> capture(
            @PathVariable Long walletId,
            @PathVariable Long storeId,
            @RequestHeader(value = "X-Internal-Auth", required = false) String authToken,
            @RequestBody FundsCaptureRequest request
    ) {
        validateInternalAuth(authToken);
        FundsResponse response = internalWalletService.capture(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{walletId}/stores/{storeId}/restore")
    public ResponseEntity<Void> restore(
            @PathVariable Long walletId,
            @PathVariable Long storeId,
            @RequestHeader(value = "X-Internal-Auth", required = false) String authToken,
            @RequestBody RestoreRequest request
    ) {
        validateInternalAuth(authToken);
        internalWalletService.restore(walletId, storeId, request.amount());
        return ResponseEntity.ok().build();
    }

    private void validateInternalAuth(String authToken) {
        if (!INTERNAL_AUTH_TOKEN.equals(authToken)) {
            log.warn("Internal API 인증 실패: 잘못된 토큰");
            throw new IllegalArgumentException("Internal API 인증 실패");
        }
    }

    public record RestoreRequest(Long amount) {}
}
```

#### InternalWalletService.java

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class InternalWalletService {

    private final WalletRepository walletRepository;
    private final WalletStoreBalanceRepository balanceRepository;
    private final StoreRepository storeRepository;
    private final CustomerRepository customerRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionItemRepository transactionItemRepository;
    private final MenuRepository menuRepository;

    @Transactional(readOnly = true)
    public WalletBalanceResponse getBalance(Long walletId, Long storeId) {
        WalletStoreBalance balance = balanceRepository
            .findByWalletIdAndStoreId(walletId, storeId)
            .orElse(null);

        BigDecimal balanceAmount = balance != null
                ? BigDecimal.valueOf(balance.getBalance())
                : BigDecimal.ZERO;

        return WalletBalanceResponse.builder()
                .walletId(walletId)
                .storeId(storeId)
                .balance(balanceAmount)
                .build();
    }

    @Transactional
    public FundsResponse capture(FundsCaptureRequest request) {
        // 1. 엔티티 조회
        Wallet wallet = walletRepository.findById(request.getWalletId())
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));
        Store store = storeRepository.findById(request.getStoreId())
                .orElseThrow(() -> new CustomException(ErrorCode.STORE_NOT_FOUND));
        Customer customer = customerRepository
                .findByCustomerIdAndDeletedAtIsNull(request.getCustomerId())
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 2. 잔액 조회 (행잠금)
        WalletStoreBalance balance = balanceRepository
                .lockByWalletIdAndStoreId(request.getWalletId(), request.getStoreId())
                .orElse(null);

        if (balance == null || balance.getBalance() < request.getAmount()) {
            return FundsResponse.insufficient();
        }

        // 3. 잔액 차감
        balance.subtractBalance(request.getAmount());

        // 4. 거래 내역 생성
        Transaction transaction = transactionRepository.save(
                Transaction.builder()
                        .wallet(wallet)
                        .customer(customer)
                        .store(store)
                        .transactionType(TransactionType.USE)
                        .amount(request.getAmount())
                        .build()
        );

        // 5. 거래 항목 생성
        if (request.getItems() != null) {
            for (FundsCaptureRequest.ItemSnapshot item : request.getItems()) {
                Menu menu = item.getMenuId() != null
                    ? menuRepository.findById(item.getMenuId()).orElse(null)
                    : null;

                TransactionItem txItem = TransactionItem.of(
                        transaction,
                        request.getStoreId(),
                        menu,
                        item.getMenuName(),
                        item.getUnitPrice(),
                        item.getQuantity()
                );
                transactionItemRepository.save(txItem);
            }
        }

        return FundsResponse.ok(transaction.getTransactionId());
    }

    @Transactional
    public void restore(Long walletId, Long storeId, Long amount) {
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException(ErrorCode.STORE_NOT_FOUND));

        WalletStoreBalance balance = balanceRepository
                .lockByWalletIdAndStoreId(walletId, storeId)
                .orElseGet(() -> balanceRepository.save(
                        WalletStoreBalance.builder()
                                .wallet(wallet)
                                .store(store)
                                .balance(0L)
                                .build()
                ));

        balance.addBalance(amount);
    }
}
```

#### Security 설정 변경 (SecurityConfig.java)

```java
public static final String[] ALLOW_URLS = {
    // ... 기존 설정 ...

    // Internal API - 마이크로서비스 간 통신용 (X-Internal-Auth 헤더로 보호)
    "/internal/**"
};
```

---

### 2.4 결제 의도 (Payment Intent) 도메인

#### PaymentIntent.java

```java
@Entity
@Table(name = "payment_intents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class PaymentIntent {

    @Id
    @Column(name = "intent_id", columnDefinition = "BINARY(16)")
    private UUID intentId;

    @Column(name = "qr_token_id", nullable = false, length = 100)
    private String qrTokenId;

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Column(name = "owner_id", nullable = false)
    private Long ownerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "total_amount", nullable = false)
    private Long totalAmount;

    @Column(name = "toss_payment_key", length = 200)
    private String tossPaymentKey;

    @Column(name = "toss_order_id", length = 100)
    private String tossOrderId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Version
    private Long version;

    @OneToMany(mappedBy = "paymentIntent", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PaymentIntentItem> items = new ArrayList<>();

    // 비즈니스 메서드
    public void approve(String tossPaymentKey) {
        this.status = PaymentStatus.APPROVED;
        this.tossPaymentKey = tossPaymentKey;
    }

    public void decline(String reason) {
        this.status = PaymentStatus.DECLINED;
    }

    public void cancel() {
        this.status = PaymentStatus.CANCELED;
    }

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt);
    }
}
```

#### PaymentIntentService.java (핵심 로직)

```java
@Service
@RequiredArgsConstructor
@Transactional
public class PaymentIntentService {

    private final PaymentIntentRepository intentRepository;
    private final PaymentIntentItemRepository itemRepository;
    private final IdempotencyService idempotencyService;
    private final WalletClient walletClient;
    private final StoreClient storeClient;
    private final MenuClient menuClient;
    private final CustomerClient customerClient;
    private final QrPaymentClient qrPaymentClient;
    private final NotificationClient notificationClient;
    private final FundsService fundsService;

    @Qualifier("canonicalObjectMapper")
    private final ObjectMapper canonicalObjectMapper;

    /**
     * 결제 의도 생성 (점주가 호출)
     */
    public IdempotentResult<PaymentIntentDetailResponse> initiate(
            String qrTokenId,
            String idemKey,
            Long ownerId,
            PaymentInitiateRequest request
    ) {
        // 1. 멱등성 체크
        // 2. QR 토큰 검증 (qr-payment-service)
        // 3. 매장/메뉴 검증
        // 4. 잔액 확인
        // 5. PaymentIntent 생성
        // 6. 고객에게 알림 발송
        // ...
    }

    /**
     * 결제 승인 (고객이 호출)
     */
    public IdempotentResult<PaymentIntentDetailResponse> approve(
            UUID intentId,
            String idemKey,
            Long customerId,
            ApproveRequest request
    ) {
        // 1. 멱등성 체크
        // 2. PaymentIntent 조회 및 검증
        // 3. PIN 검증
        // 4. 잔액 차감 (모놀리스 Internal API)
        // 5. 상태 변경 (APPROVED)
        // 6. 점주/고객 알림
        // ...
    }
}
```

---

### 2.5 멱등성 처리

#### IdempotencyKey.java

```java
@Entity
@Table(name = "idempotency_keys")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdempotencyKey {

    @Id
    @Column(name = "idem_key_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long idemKeyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20)
    private IdemActorType actorType;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(name = "method", nullable = false, length = 10)
    private String method;

    @Column(name = "path", nullable = false, length = 255)
    private String path;

    @Column(name = "key_uuid", nullable = false, columnDefinition = "BINARY(16)")
    private UUID keyUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private IdemStatus status;

    @Column(name = "body_hash", columnDefinition = "BINARY(32)")
    private byte[] bodyHash;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "response_json", columnDefinition = "JSON")
    @Convert(converter = JsonNodeConverter.class)
    private JsonNode responseJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
```

#### IdempotencyService.java

```java
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyKeyRepository repository;

    @Qualifier("canonicalObjectMapper")
    private final ObjectMapper canonicalObjectMapper;

    public static byte[] sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    @Transactional
    public IdemBegin beginOrLoad(
            IdemActorType actorType,
            Long actorId,
            String method,
            String path,
            UUID keyUuid,
            byte[] bodyHash
    ) {
        Optional<IdempotencyKey> existing = repository
            .findByActorTypeAndActorIdAndMethodAndPathAndKeyUuid(
                actorType, actorId, method, path, keyUuid
            );

        if (existing.isPresent()) {
            return new IdemBegin(existing.get(), false);
        }

        IdempotencyKey newKey = IdempotencyKey.builder()
                .actorType(actorType)
                .actorId(actorId)
                .method(method)
                .path(path)
                .keyUuid(keyUuid)
                .status(IdemStatus.IN_PROGRESS)
                .bodyHash(bodyHash)
                .build();

        return new IdemBegin(repository.save(newKey), true);
    }

    public boolean isBodyConflict(IdempotencyKey key, byte[] newBodyHash) {
        if (key.getBodyHash() == null || newBodyHash == null) {
            return false;
        }
        return !Arrays.equals(key.getBodyHash(), newBodyHash);
    }

    @Transactional
    public void completeCharge(IdempotencyKey key, int httpStatus, Object responseBody) {
        key.setStatus(IdemStatus.DONE);
        key.setHttpStatus(httpStatus);
        try {
            key.setResponseJson(canonicalObjectMapper.valueToTree(responseBody));
        } catch (Exception e) {
            // 로깅
        }
        repository.save(key);
    }
}
```

---

## 3. 인프라 설정

### 3.1 docker-compose.msa.yml

```yaml
version: '3.8'

services:
  # API Gateway (Nginx)
  nginx:
    image: nginx:alpine
    container_name: keeping-nginx
    ports:
      - "80:80"
    volumes:
      - ./gateway/nginx.conf:/etc/nginx/nginx.conf:ro
    depends_on:
      - monolith
      - qr-payment
      - payment
    networks:
      - keeping-network

  # 기존 모놀리스
  monolith:
    image: ${DOCKER_USERNAME}/keeping-backend:latest
    container_name: keeping-monolith
    depends_on:
      mysql:
        condition: service_healthy
      redis:
        condition: service_healthy
    ports:
      - "8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: prod,loadtest
      # ... 기타 환경변수
    networks:
      - keeping-network

  # QR-Payment 서비스
  qr-payment:
    build:
      context: ./services/qr-payment-service
      dockerfile: Dockerfile
    container_name: keeping-qr-payment
    depends_on:
      redis:
        condition: service_healthy
    expose:
      - "8081"
    environment:
      SPRING_PROFILES_ACTIVE: docker
      REDIS_HOST: redis
      JWT_SECRET: ${JWT_SECRET}
      MONOLITH_URL: http://monolith:8080
    networks:
      - keeping-network

  # Payment 서비스 (신규)
  payment:
    build:
      context: ./services/payment-service
      dockerfile: Dockerfile
    container_name: keeping-payment
    depends_on:
      mysql:
        condition: service_healthy
    expose:
      - "8082"
    environment:
      SPRING_PROFILES_ACTIVE: docker
      DB_URL: jdbc:mysql://mysql:3306/payment_service?useSSL=false&serverTimezone=Asia/Seoul&characterEncoding=UTF-8&allowPublicKeyRetrieval=true
      DB_USERNAME: root
      DB_PASSWORD: ${MYSQL_ROOT_PASSWORD:-1234}
      JWT_SECRET: ${JWT_SECRET}
      MONOLITH_URL: http://monolith:8080
      QR_PAYMENT_URL: http://qr-payment:8081
      TOSS_SECRET_KEY: ${TOSS_SECRET_KEY}
      INTERNAL_AUTH_TOKEN: internal-service-token-12345
    networks:
      - keeping-network

  # MySQL
  mysql:
    image: mysql:8.0
    container_name: keeping-mysql
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:-1234}
      MYSQL_DATABASE: keeping
    volumes:
      - mysql_data:/var/lib/mysql
      - ./mysql/init:/docker-entrypoint-initdb.d:ro
    networks:
      - keeping-network

  # Redis
  redis:
    image: redis:7-alpine
    container_name: keeping-redis
    networks:
      - keeping-network

volumes:
  mysql_data:

networks:
  keeping-network:
    driver: bridge
```

### 3.2 gateway/nginx.conf

```nginx
events {
    worker_connections 1024;
}

http {
    # 백엔드 서버 그룹
    upstream monolith {
        server monolith:8080;
    }

    upstream qr-payment {
        server qr-payment:8081;
    }

    upstream payment {
        server payment:8082;
    }

    server {
        listen 80;
        server_name localhost;

        # 헬스체크
        location /health {
            return 200 'OK';
            add_header Content-Type text/plain;
        }

        # CPQR 결제 의도 생성 → Payment Service
        location ~ ^/cpqr/([^/]+)/initiate$ {
            proxy_pass http://payment;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header Authorization $http_authorization;
            proxy_set_header Idempotency-Key $http_idempotency_key;
            proxy_set_header X-Owner-Id $http_x_owner_id;
        }

        # 결제 승인 → Payment Service
        location ~ ^/payments/([^/]+)/approve$ {
            proxy_pass http://payment;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header Authorization $http_authorization;
            proxy_set_header Idempotency-Key $http_idempotency_key;
            proxy_set_header X-Customer-Id $http_x_customer_id;
        }

        # 결제 의도 상세 조회 → Payment Service
        location ~ ^/api/payments/intent/([^/]+)$ {
            proxy_pass http://payment;
            proxy_set_header Host $host;
            proxy_set_header Authorization $http_authorization;
        }

        # QR API → 모놀리스
        location /api/qr {
            proxy_pass http://monolith;
            proxy_set_header Host $host;
            proxy_set_header Authorization $http_authorization;
        }

        # 나머지 → 모놀리스
        location / {
            proxy_pass http://monolith;
            proxy_set_header Host $host;
            proxy_set_header Authorization $http_authorization;
        }
    }
}
```

### 3.3 MySQL 초기화 스크립트

**mysql/init/01-create-payment-db.sql**

```sql
-- payment_service 데이터베이스 생성
CREATE DATABASE IF NOT EXISTS payment_service
CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci;
```

---

## 4. API 명세

### 4.1 Payment Service API

| Method | Endpoint | 설명 | 호출자 |
|--------|----------|------|--------|
| POST | `/cpqr/{qrTokenId}/initiate` | 결제 의도 생성 | 점주 |
| POST | `/payments/{intentId}/approve` | 결제 승인 | 고객 |
| GET | `/api/payments/intent/{intentId}` | 결제 의도 조회 | 점주/고객 |

### 4.2 Internal API (모놀리스)

| Method | Endpoint | 설명 |
|--------|----------|------|
| GET | `/internal/wallets/{walletId}/stores/{storeId}/balance` | 잔액 조회 |
| POST | `/internal/wallets/{walletId}/stores/{storeId}/capture` | 자금 캡처 |
| POST | `/internal/wallets/{walletId}/stores/{storeId}/restore` | 잔액 복원 |
| GET | `/internal/stores/{storeId}` | 매장 정보 조회 |
| POST | `/internal/menus/batch` | 메뉴 일괄 조회 |
| GET | `/internal/customers/{customerId}` | 고객 정보 조회 |
| POST | `/internal/customers/{customerId}/pin-verify` | PIN 검증 |
| POST | `/internal/notifications/send` | 알림 발송 |

---

## 5. 결제 플로우

```
┌─────────┐      ┌─────────┐      ┌─────────────┐      ┌──────────┐
│  점주   │      │ Nginx   │      │  Payment    │      │ Monolith │
│  (POS)  │      │ Gateway │      │  Service    │      │          │
└────┬────┘      └────┬────┘      └──────┬──────┘      └────┬─────┘
     │                │                   │                  │
     │ 1. QR 스캔     │                   │                  │
     │───────────────>│                   │                  │
     │                │ 2. 결제 의도 생성 │                  │
     │                │──────────────────>│                  │
     │                │                   │ 3. QR 토큰 검증  │
     │                │                   │─────────────────>│ (qr-payment)
     │                │                   │                  │
     │                │                   │ 4. 잔액 확인     │
     │                │                   │─────────────────>│
     │                │                   │                  │
     │                │                   │ 5. Intent 저장   │
     │                │                   │<─────────────────│
     │                │<──────────────────│                  │
     │<───────────────│                   │                  │
     │                │                   │                  │
     │                │                   │                  │
┌────┴────┐      ┌────┴────┐      ┌──────┴──────┐      ┌────┴─────┐
│  고객   │      │ Nginx   │      │  Payment    │      │ Monolith │
│  (App)  │      │ Gateway │      │  Service    │      │          │
└────┬────┘      └────┬────┘      └──────┬──────┘      └────┬─────┘
     │                │                   │                  │
     │ 6. 결제 승인   │                   │                  │
     │───────────────>│                   │                  │
     │                │──────────────────>│                  │
     │                │                   │ 7. PIN 검증      │
     │                │                   │─────────────────>│
     │                │                   │                  │
     │                │                   │ 8. 자금 캡처     │
     │                │                   │─────────────────>│
     │                │                   │                  │
     │                │                   │ 9. 상태 변경     │
     │                │                   │  (APPROVED)      │
     │                │<──────────────────│                  │
     │<───────────────│                   │                  │
     │                │                   │                  │
```

---

## 6. 검증 방법

### 6.1 서비스 시작

```bash
# 1. 전체 서비스 시작
docker compose -f docker-compose.msa.yml up -d

# 2. 서비스 상태 확인
docker compose -f docker-compose.msa.yml ps

# 3. 로그 확인
docker logs keeping-payment -f
```

### 6.2 헬스체크

```bash
# Nginx Gateway
curl http://localhost/health

# Payment Service
curl http://localhost:8082/actuator/health

# Monolith
curl http://localhost:8080/actuator/health
```

### 6.3 E2E 테스트 플로우

```bash
# 1. 고객 로그인 -> Access Token 획득

# 2. QR 토큰 생성 (qr-payment-service)
curl -X POST http://localhost/api/qr/new \
  -H "Authorization: Bearer {customer_token}" \
  -H "Content-Type: application/json" \
  -d '{"walletId": 1, "mode": "CPQR"}'

# 3. 결제 의도 생성 (payment-service)
curl -X POST http://localhost/cpqr/{qrTokenId}/initiate \
  -H "Authorization: Bearer {owner_token}" \
  -H "Idempotency-Key: {uuid}" \
  -H "X-Owner-Id: 1" \
  -H "Content-Type: application/json" \
  -d '{
    "items": [
      {"menuId": 1, "quantity": 2},
      {"menuId": 2, "quantity": 1}
    ]
  }'

# 4. 결제 승인 (payment-service)
curl -X POST http://localhost/payments/{intentId}/approve \
  -H "Authorization: Bearer {customer_token}" \
  -H "Idempotency-Key: {uuid}" \
  -H "X-Customer-Id: 1" \
  -H "Content-Type: application/json" \
  -d '{"pin": "123456"}'
```

---

## 7. 주의사항

### 7.1 트랜잭션 일관성
- ACL 호출 실패 시 보상 트랜잭션 필요
- 멱등성 키를 통한 중복 요청 방지

### 7.2 보안
- Internal API는 `X-Internal-Auth` 헤더로 보호
- 외부에서 `/internal/**` 경로 접근 불가 (Nginx에서 라우팅하지 않음)

### 7.3 타임아웃
- RestTemplate 5초 타임아웃 설정
- 장시간 작업 시 비동기 처리 고려

### 7.4 데이터베이스
- payment_service: Payment Service 전용 스키마
- keeping: 기존 모놀리스 스키마
- 두 DB 간 직접 JOIN 불가 (ACL 통해 데이터 조회)

---

## 8. 파일 목록

### 8.1 Payment Service 신규 파일

```
services/payment-service/
├── build.gradle
├── settings.gradle
├── Dockerfile
├── src/main/resources/application.yml
└── src/main/java/com/ssafy/keeping/payment/
    ├── PaymentServiceApplication.java
    ├── config/
    │   ├── RestTemplateConfig.java
    │   ├── JpaConfig.java
    │   └── ObjectMapperConfig.java
    ├── common/
    │   ├── IdUtil.java
    │   ├── response/ApiResponse.java
    │   └── exception/
    │       ├── ErrorCode.java
    │       ├── CustomException.java
    │       └── GlobalExceptionHandler.java
    ├── acl/
    │   ├── WalletClient.java
    │   ├── StoreClient.java
    │   ├── MenuClient.java
    │   ├── CustomerClient.java
    │   ├── NotificationClient.java
    │   ├── QrPaymentClient.java
    │   └── dto/ (9개 파일)
    ├── domain/
    │   ├── intent/
    │   │   ├── model/ (2개)
    │   │   ├── repository/ (2개)
    │   │   ├── constant/ (1개)
    │   │   ├── dto/ (5개)
    │   │   ├── canonical/ (2개)
    │   │   ├── service/ (2개)
    │   │   └── controller/ (2개)
    │   └── idempotency/
    │       ├── model/ (2개)
    │       ├── constant/ (2개)
    │       ├── dto/ (1개)
    │       ├── repository/ (1개)
    │       └── service/ (1개)
    ├── gateway/
    │   ├── PaymentGateway.java
    │   ├── PaymentGatewayFactory.java
    │   ├── PaymentProvider.java
    │   ├── dto/ (4개)
    │   └── impl/TossPaymentGateway.java
    └── toss/
        ├── TossPaymentClient.java
        ├── config/TossPaymentConfig.java
        └── dto/ (4개)
```

### 8.2 모놀리스 수정/추가 파일

```
src/main/java/com/ssafy/keeping/domain/internal/
├── controller/
│   ├── InternalWalletController.java (신규)
│   ├── InternalStoreController.java (신규)
│   ├── InternalMenuController.java (신규)
│   ├── InternalCustomerController.java (신규)
│   └── InternalNotificationController.java (신규)
├── service/
│   └── InternalWalletService.java (신규)
└── dto/ (11개 파일, 신규)

src/main/java/.../auth/security/config/SecurityConfig.java (수정)
  - ALLOW_URLS에 "/internal/**" 추가
```

### 8.3 인프라 파일

```
docker-compose.msa.yml (수정 - payment 서비스 추가)
gateway/nginx.conf (수정 - payment 라우팅 추가)
mysql/init/01-create-payment-db.sql (신규)
```

---

## 9. 변경 이력

| 날짜 | 작업 내용 |
|------|----------|
| 2024-02-03 | Payment Service MSA 분리 작업 완료 |
| | - payment-service 프로젝트 구조 생성 |
| | - ACL 클라이언트 구현 |
| | - 모놀리스 Internal API 구현 |
| | - Docker/Nginx 설정 업데이트 |
| | - 빌드 검증 완료 |
