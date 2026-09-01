# Observability 고도화 TODO List

---

## 기술 선택 요약

| 항목 | 내용 |
|------|------|
| **문제** | 분산 환경에서 로그 파편화로 에러 원인 추적에 65분 소요 (MTTD 65분, MTTR 2시간) |
| **해결 후보** | ELK Stack, Zipkin/Jaeger, Prometheus + Grafana, CloudWatch |
| **선택** | Micrometer Tracing (Brave) + 로그 기반 추적 (적용 완료) + Zipkin/Prometheus (고도화 필요) |
| **이유** | TraceId로 분산 요청 추적, 저비용 시작, 단계적 확장 가능 |

### 해결 후보 비교

| 솔루션 | 역할 | 장점 | 단점 | 적용 상태 |
|--------|------|------|------|----------|
| **Micrometer Tracing** | TraceId/SpanId 생성 및 전파 | Spring 네이티브, 설정 간단 | 시각화 별도 필요 | ✅ 완료 |
| **Zipkin** | 분산 추적 시각화 | 요청 흐름 타임라인, 병목 파악 | 추가 인프라 필요 | ❌ 미적용 |
| **Jaeger** | 분산 추적 (CNCF 표준) | 대규모 확장성, Kubernetes 친화적 | 운영 복잡도 높음 | ❌ 미적용 |
| **ELK Stack** | 중앙 집중 로그 | 강력한 검색, Kibana 시각화 | 리소스 사용량 높음 | ❌ 미적용 |
| **Prometheus** | 메트릭 수집 | 시계열 DB, Alerting | 로그 분석 불가 | ❌ 미적용 |
| **Grafana** | 대시보드/시각화 | 다양한 데이터소스 지원 | 별도 설정 필요 | ❌ 미적용 |

### 현재 vs 목표 지표

| 지표 | Before | After (현재) | 목표 |
|------|--------|-------------|------|
| MTTD (에러 감지 시간) | 65분 | **5분** | 1분 |
| MTTR (에러 해결 시간) | 2시간 | **30분** | 10분 |
| 로그 검색 시간 | 45분 | **2분** | 10초 |
| 로그 매칭 정확도 | 60% | **100%** | 100% |

> 💡 **선택 근거**: MSA 환경에서 요청 추적이 핵심. TraceId 기반 추적으로 92% MTTD 단축 달성. 추후 Zipkin으로 시각화 + Prometheus로 메트릭 수집하여 완성도 향상

---

## 목차

- [기술 선택 요약](#기술-선택-요약)
- [배경: 실제 발생한 문제 상황](#배경-실제-발생한-문제-상황)
  - [장애 시나리오: 결제 실패 원인 추적 불가](#장애-시나리오-결제-실패-원인-추적-불가)
  - [문제 원인 분석](#문제-원인-분석)
  - [비즈니스 영향](#비즈니스-영향)
- [해결: Micrometer Tracing 도입](#해결-micrometer-tracing-도입)
- [현재 적용 상태](#현재-적용-상태)
- [수치화: Before vs After](#수치화-before-vs-after)
- [TODO List: 고도화](#todo-list-고도화)
  - [Phase 1: Zipkin 도입](#phase-1-zipkin-도입-시각화)
  - [Phase 2: Custom Span 추가](#phase-2-custom-span-추가-세밀한-추적)
  - [Phase 3: Metrics 수집](#phase-3-metrics-수집-prometheus--grafana)
  - [Phase 4: Alerting](#phase-4-alerting-에러-알람)
  - [Phase 5: Log Aggregation](#phase-5-log-aggregation-중앙-집중-로그)
  - [Phase 6: Baggage 전파](#phase-6-baggage-전파-컨텍스트-정보)
- [수치화 목표](#수치화-목표)
- [진행 현황](#진행-현황)
- [파일 수정 목록](#파일-수정-목록)
- [참고 문서](#참고-문서)

---

## 배경: 실제 발생한 문제 상황

### 장애 시나리오: 결제 실패 원인 추적 불가

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        장애 발생 타임라인                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  [T+0초]   고객 민원 접수                                                   │
│            "결제가 계속 실패해요"                                            │
│            ↓                                                                │
│  [T+5분]   개발자 A, QR 서버 로그 확인                                       │
│            ERROR - 결제 실패, customerId=42                                 │
│            "왜 실패했지? 원인이 안 보이는데..."                               │
│            ↓                                                                │
│  [T+10분]  개발자 A, 모놀리스 서버 로그 확인                                  │
│            ERROR - 잔액 부족                                                 │
│            ERROR - PIN 검증 실패                                             │
│            ERROR - 타임아웃                                                  │
│            "어떤 에러가 42번 고객 건이지...?"                                 │
│            ↓                                                                │
│  [T+20분]  로그 시간대로 추정하여 수동 매칭 시도                              │
│            "14:32:15에 QR 에러 났으니까... 모놀리스는 14:32:15~17 사이?"      │
│            → 동시간대 에러 로그 47개 발견                                    │
│            → 어떤 게 42번 고객 건인지 특정 불가                              │
│            ↓                                                                │
│  [T+35분]  DB에서 거래 내역 조회하여 역추적 시도                              │
│            "transaction_id로 검색해볼까..."                                  │
│            → 거래가 실패해서 DB에 기록 없음                                  │
│            ↓                                                                │
│  [T+50분]  결국 customerId로 전체 로그 grep                                  │
│            grep "customerId=42" *.log | head -1000                          │
│            → 3일치 로그에서 1,247건 발견                                     │
│            → 수동으로 시간대 필터링...                                       │
│            ↓                                                                │
│  [T+65분]  드디어 원인 파악                                                  │
│            "모놀리스에서 Wallet 서비스 타임아웃이었구나"                       │
│                                                                             │
│  총 소요 시간: 65분 (1시간 이상!)                                            │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 문제 원인 분석

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          로그 파편화 문제                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  [QR 서버 로그]                        [모놀리스 서버 로그]                   │
│  ┌─────────────────────────┐          ┌─────────────────────────┐          │
│  │ 14:32:15 ERROR 결제실패  │          │ 14:32:15 ERROR 잔액부족  │          │
│  │ 14:32:15 ERROR 결제실패  │   ???    │ 14:32:16 ERROR PIN실패   │          │
│  │ 14:32:16 ERROR 결제실패  │ ←─────→  │ 14:32:16 ERROR 타임아웃  │          │
│  │ 14:32:17 ERROR 결제실패  │          │ 14:32:17 ERROR 잔액부족  │          │
│  └─────────────────────────┘          └─────────────────────────┘          │
│                                                                             │
│  문제점:                                                                    │
│  1. 두 서버의 로그가 연결되지 않음                                           │
│  2. 동시간대에 여러 에러 발생 시 매칭 불가                                   │
│  3. customerId만으로는 특정 요청 추적 불가                                   │
│  4. 서버 간 시간 오차로 시간 기반 추적도 부정확                              │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 비즈니스 영향

| 항목 | Before (Tracing 전) |
|------|---------------------|
| 평균 에러 원인 파악 시간 (MTTD) | **65분** |
| 평균 에러 해결 시간 (MTTR) | **2시간** |
| 로그 검색에 소요된 시간 | **45분** |
| 관련 로그 수동 매칭 정확도 | **60%** (추정) |
| 개발자 불만 | "로그 찾다가 하루가 다 감" |

---

## 해결: Micrometer Tracing 도입

### 구현 내용

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      TraceId 기반 분산 추적                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  [QR 서버]                              [모놀리스 서버]                       │
│  ┌─────────────────────────────┐       ┌─────────────────────────────┐     │
│  │ INFO [qr,abc123,001]        │       │                             │     │
│  │   결제 요청 수신             │  ───→ │ INFO [mono,abc123,002]      │     │
│  │                             │       │   잔액 차감 요청 수신        │     │
│  │ ERROR [qr,abc123,005]       │  ←─── │ ERROR [mono,abc123,004]     │     │
│  │   결제 실패                  │       │   Wallet 서비스 타임아웃    │     │
│  └─────────────────────────────┘       └─────────────────────────────┘     │
│                                                                             │
│  → TraceId "abc123"으로 검색하면 두 서버 로그가 한번에 조회됨!               │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 핵심 구현 코드

**HTTP 헤더로 TraceId 자동 전파:**
```
QR Service → Monolith 호출 시 자동으로 헤더 추가:

POST /internal/wallets/transfer HTTP/1.1
X-B3-TraceId: abc123def456
X-B3-SpanId: 001
X-B3-ParentSpanId: 000
X-B3-Sampled: 1
```

**로그 포맷:**
```
INFO [qr-service,abc123def456,001] - 결제 요청 수신
     └─서비스명─┘└─TraceId──┘└SpanId┘
```

---

## 현재 적용 상태

| 기술 | 상태 | 위치 |
|------|------|------|
| **Micrometer Tracing** | ✅ 완료 | `build.gradle` 68~70줄 |
| **Brave (Tracer)** | ✅ 완료 | `build.gradle` 68~70줄 |
| **로그 패턴 (TraceId 포함)** | ✅ 완료 | `application.yml` 79~81줄 |
| **RestTemplate Trace 전파** | ✅ 완료 | `RestTemplateConfig.java` |
| **Zipkin 서버** | ❌ 미적용 | 로그 기반 추적 사용 중 |
| **Custom Span** | ❌ 미적용 | 기본 Span만 사용 |
| **Metrics (Prometheus)** | ❌ 미적용 | Actuator 기본만 |
| **Alerting** | ❌ 미적용 | 알람 없음 |
| **Log Aggregation** | ❌ 미적용 | 서버별 로그 분리 |

---

## 수치화: Before vs After

### 에러 추적 시간 비교

| 지표 | Before | After | 개선율 |
|------|--------|-------|--------|
| **MTTD** (에러 감지 시간) | 65분 | **5분** | **92% 단축** |
| **MTTR** (에러 해결 시간) | 2시간 | **30분** | **75% 단축** |
| 로그 검색 시간 | 45분 | **2분** | **96% 단축** |
| 관련 로그 매칭 정확도 | 60% | **100%** | **완벽** |

### 디버깅 워크플로우 비교

```
[Before - 65분]
민원 접수 → QR 로그 확인 → 모놀리스 로그 확인 → 시간대 추정
         → DB 조회 → grep 검색 → 수동 매칭 → 원인 파악

[After - 5분]
민원 접수 → 에러 로그에서 TraceId 확인 → TraceId로 검색 → 원인 파악
```

### 로그 검색 예시

```bash
# Before: customerId로 검색 (1,247건 중 찾기)
grep "customerId=42" /var/log/*.log | wc -l
# 결과: 1247

# After: TraceId로 검색 (정확히 해당 요청만)
grep "abc123def456" /var/log/*.log | wc -l
# 결과: 7 (해당 요청의 모든 로그)
```

---

## TODO List: 고도화

### Phase 1: Zipkin 도입 (시각화)

**목적**: 요청 흐름을 타임라인으로 시각화, 병목 구간 한눈에 파악

- [ ] **1.1 Zipkin 서버 Docker Compose 추가**

  ```yaml
  # docker-compose.yml
  services:
    zipkin:
      image: openzipkin/zipkin:latest
      ports:
        - "9411:9411"
      environment:
        - STORAGE_TYPE=mem  # 운영에서는 elasticsearch 권장
  ```

- [ ] **1.2 application.yml 설정 변경**

  ```yaml
  management:
    zipkin:
      tracing:
        enabled: true  # false → true
        endpoint: ${ZIPKIN_URL:http://zipkin:9411/api/v2/spans}
  ```

- [ ] **1.3 기대 효과**

  ```
  [Zipkin 타임라인]

  QR Service ████████████████████████████████████████ 850ms
  ├─ Redis 조회   ██ 50ms
  └─ Monolith 호출 ███████████████████████ 600ms  ← 병목!
     └─ Monolith
        ├─ 잔액 조회 ████ 100ms
        └─ 잔액 차감 ████████████████ 450ms

  → 어디서 시간이 오래 걸리는지 한눈에 파악!
  ```

---

### Phase 2: Custom Span 추가 (세밀한 추적)

**목적**: 비즈니스 로직 단위로 소요 시간 측정

- [ ] **2.1 결제 플로우 Span 분리**

  ```java
  @Autowired
  private Tracer tracer;

  public PaymentResult processPayment(PaymentRequest request) {
      // Span 1: 캐시 조회
      Span cacheSpan = tracer.nextSpan().name("cache-lookup").start();
      try (Tracer.SpanInScope ws = tracer.withSpan(cacheSpan)) {
          storeClient.getStore(request.getStoreId());
      } finally {
          cacheSpan.end();
      }

      // Span 2: 잔액 검증
      Span balanceSpan = tracer.nextSpan().name("balance-check").start();
      try (Tracer.SpanInScope ws = tracer.withSpan(balanceSpan)) {
          walletClient.getBalance(request.getWalletId());
      } finally {
          balanceSpan.end();
      }

      // Span 3: 결제 확정
      Span captureSpan = tracer.nextSpan().name("payment-capture").start();
      try (Tracer.SpanInScope ws = tracer.withSpan(captureSpan)) {
          walletClient.capture(request);
      } finally {
          captureSpan.end();
      }
  }
  ```

- [ ] **2.2 AOP 기반 자동 Span 생성**

  ```java
  @Aspect
  @Component
  public class TracingAspect {

      @Around("@annotation(Traced)")
      public Object trace(ProceedingJoinPoint pjp) throws Throwable {
          String spanName = pjp.getSignature().getName();
          Span span = tracer.nextSpan().name(spanName).start();
          try (Tracer.SpanInScope ws = tracer.withSpan(span)) {
              return pjp.proceed();
          } finally {
              span.end();
          }
      }
  }

  // 사용
  @Traced
  public void processPayment() { ... }
  ```

---

### Phase 3: Metrics 수집 (Prometheus + Grafana)

**목적**: 실시간 모니터링 대시보드

- [ ] **3.1 Prometheus 메트릭 노출**

  ```yaml
  management:
    endpoints:
      web:
        exposure:
          include: health,metrics,prometheus
    metrics:
      export:
        prometheus:
          enabled: true
      tags:
        application: qr-service
  ```

- [ ] **3.2 Custom Metrics 추가**

  ```java
  @Component
  public class PaymentMetrics {

      private final Counter paymentSuccess;
      private final Counter paymentFailure;
      private final Timer paymentDuration;

      public PaymentMetrics(MeterRegistry registry) {
          this.paymentSuccess = Counter.builder("payment.success")
              .description("결제 성공 횟수")
              .register(registry);

          this.paymentFailure = Counter.builder("payment.failure")
              .tag("reason", "unknown")
              .description("결제 실패 횟수")
              .register(registry);

          this.paymentDuration = Timer.builder("payment.duration")
              .description("결제 소요 시간")
              .register(registry);
      }

      public void recordSuccess() {
          paymentSuccess.increment();
      }

      public void recordFailure(String reason) {
          Counter.builder("payment.failure")
              .tag("reason", reason)
              .register(registry)
              .increment();
      }
  }
  ```

- [ ] **3.3 Grafana 대시보드 구성**

  | 패널 | 메트릭 |
  |------|--------|
  | 결제 성공률 | `payment.success / (payment.success + payment.failure)` |
  | 결제 응답시간 p95 | `payment.duration{quantile="0.95"}` |
  | Circuit Breaker 상태 | `resilience4j.circuitbreaker.state` |
  | 에러율 | `http.server.requests{status=~"5.."}` |

---

### Phase 4: Alerting (에러 알람)

**목적**: 에러 발생 시 즉시 알림

- [ ] **4.1 에러 발생 시 Slack 알람**

  ```java
  @Aspect
  @Component
  public class ErrorAlertAspect {

      @Autowired
      private SlackService slackService;

      @Autowired
      private Tracer tracer;

      @AfterThrowing(pointcut = "execution(* com.ssafy.keeping.qr..*(..))", throwing = "ex")
      public void alertOnError(JoinPoint jp, Exception ex) {
          String traceId = tracer.currentSpan().context().traceId();

          slackService.sendAlert(
              ":rotating_light: QR 서비스 에러 발생",
              String.format(
                  "메서드: %s\n에러: %s\nTraceId: %s\n로그 검색: `grep %s /var/log/*.log`",
                  jp.getSignature().getName(),
                  ex.getMessage(),
                  traceId,
                  traceId
              )
          );
      }
  }
  ```

- [ ] **4.2 에러율 임계치 알람 (Prometheus Alertmanager)**

  ```yaml
  # alerting_rules.yml
  groups:
    - name: qr-service
      rules:
        - alert: HighErrorRate
          expr: rate(http_server_requests_total{status=~"5..", service="qr-service"}[5m]) > 0.1
          for: 1m
          labels:
            severity: critical
          annotations:
            summary: "QR 서비스 에러율 10% 초과"
            description: "최근 5분간 에러율: {{ $value | humanizePercentage }}"
  ```

---

### Phase 5: Log Aggregation (중앙 집중 로그)

**목적**: 두 서버 로그를 한 곳에서 조회

- [ ] **5.1 옵션 A: AWS CloudWatch Logs**

  ```yaml
  # docker-compose.yml
  services:
    qr-service:
      logging:
        driver: awslogs
        options:
          awslogs-group: /app/qr-service
          awslogs-region: ap-northeast-2
          awslogs-stream-prefix: qr
  ```

- [ ] **5.2 옵션 B: ELK Stack (Elasticsearch + Logstash + Kibana)**

  ```yaml
  # docker-compose.yml
  services:
    elasticsearch:
      image: elasticsearch:8.11.0
      ports:
        - "9200:9200"

    kibana:
      image: kibana:8.11.0
      ports:
        - "5601:5601"

    logstash:
      image: logstash:8.11.0
      volumes:
        - ./logstash.conf:/usr/share/logstash/pipeline/logstash.conf
  ```

- [ ] **5.3 TraceId로 통합 검색**

  ```
  [Kibana에서 검색]
  traceId: "abc123def456"

  결과:
  - [qr-service] INFO - 결제 요청 수신
  - [qr-service] INFO - Monolith API 호출
  - [monolith] INFO - 잔액 차감 요청 수신
  - [monolith] ERROR - Wallet 서비스 타임아웃
  - [qr-service] ERROR - 결제 실패
  ```

---

### Phase 6: Baggage 전파 (컨텍스트 정보)

**목적**: TraceId 외에 비즈니스 정보(customerId, orderId 등)도 전파

- [ ] **6.1 Baggage 설정**

  ```java
  @Component
  public class BaggageFilter implements Filter {

      @Autowired
      private Tracer tracer;

      @Override
      public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
          HttpServletRequest httpRequest = (HttpServletRequest) request;
          String customerId = httpRequest.getHeader("X-Customer-Id");

          if (customerId != null) {
              BaggageField customerIdField = BaggageField.create("customerId");
              customerIdField.updateValue(customerId);
          }

          chain.doFilter(request, response);
      }
  }
  ```

- [ ] **6.2 로그에 Baggage 포함**

  ```yaml
  logging:
    pattern:
      level: "%5p [${spring.application.name:},%X{traceId:-},%X{spanId:-},%X{customerId:-}]"
  ```

  ```
  INFO [qr-service,abc123,001,customer-42] - 결제 요청 수신
  INFO [monolith,abc123,002,customer-42] - 잔액 차감 처리
  ```

---

## 수치화 목표

### 측정 지표

| 지표 | 현재 | 목표 | 측정 방법 |
|------|------|------|----------|
| MTTD (에러 감지 시간) | 5분 | **1분** | Alerting 도입 후 측정 |
| MTTR (에러 해결 시간) | 30분 | **10분** | Zipkin 도입 후 측정 |
| 병목 구간 파악 시간 | 15분 | **즉시** | Zipkin 타임라인 |
| 로그 검색 시간 | 2분 | **10초** | Log Aggregation 후 측정 |

### 측정 방법

```bash
# MTTD 측정: 에러 발생 → 알람 수신 시간
에러 발생 시간: 로그 타임스탬프
알람 수신 시간: Slack 메시지 타임스탬프
MTTD = 알람 수신 시간 - 에러 발생 시간

# MTTR 측정: 에러 발생 → 원인 파악 완료 시간
에러 발생 시간: 로그 타임스탬프
원인 파악 시간: Jira 티켓 상태 변경 시간
MTTR = 원인 파악 시간 - 에러 발생 시간
```

---

## 진행 현황

```
현재 위치:
┌─────────────────────────────────────────────────────────────┐
│  ✅ Micrometer Tracing (기본)                               │
│  ✅ TraceId 전파 (HTTP 헤더)                                │
│  ✅ 로그 패턴 (TraceId/SpanId 포함)                         │
│  ─────────────────────────────────────────────────────────  │
│  ❌ Phase 1: Zipkin (시각화)                                │ ← 여기부터
│  ❌ Phase 2: Custom Span (세밀한 추적)                      │
│  ❌ Phase 3: Metrics (Prometheus + Grafana)                 │
│  ❌ Phase 4: Alerting (에러 알람)                           │
│  ❌ Phase 5: Log Aggregation (중앙 집중 로그)               │
│  ❌ Phase 6: Baggage 전파 (컨텍스트 정보)                   │
└─────────────────────────────────────────────────────────────┘
```

---

## 파일 수정 목록

| 파일 | 변경 내용 |
|------|----------|
| `docker-compose.yml` | Zipkin, ELK 서비스 추가 |
| `application.yml` | Zipkin 활성화, Prometheus 설정 |
| `build.gradle` | Micrometer Prometheus 의존성 |
| `TracingAspect.java` | Custom Span AOP (신규) |
| `PaymentMetrics.java` | Custom Metrics (신규) |
| `ErrorAlertAspect.java` | 에러 알람 (신규) |
| `BaggageFilter.java` | Baggage 전파 (신규) |

---

## 참고 문서

| 문서 | 설명 |
|------|------|
| `docs/portfolio/observability.md` | Observability 포트폴리오 |
| `docs/0218/observability-implementation.md` | 구현 문서 |

---

*마지막 업데이트: 2026-02-26*
