# Notification Service 분리 및 무중단 배포 시나리오

## 1. 분리 대상 선정 근거

### 1.1 모놀리스 도메인 분석 결과

| 도메인 | 파일 수 | 분리 난이도 | 외부 API | 추천 순위 |
|--------|--------|------------|----------|----------|
| **Notification** | 13 | LOW (3/10) | Firebase/FCM | **1위** |
| Store | 17 | MEDIUM (6/10) | - | 2위 |
| Charge | 28 | MEDIUM (7/10) | Toss | 3위 |
| Wallet | 24 | HIGH (8/10) | - | 4위 |
| Payment | 40 | VERY HIGH (9/10) | Toss | 최후 |

### 1.2 Notification Service 선정 이유

1. **낮은 분리 난이도**
   - 다른 도메인에 대한 의존성 최소 (Auth, User 조회만)
   - 자체 완결적인 엔티티 (Notification, FcmToken)
   - 실패해도 핵심 비즈니스 로직에 영향 없음

2. **명확한 서비스 경계**
   - 단일 책임: 알림 전송 (FCM, SSE, Email)
   - 비동기 처리에 적합한 구조
   - 다른 도메인에서 호출만 받음 (inbound only)

3. **확장성 가치**
   - 푸시 알림 대량 발송 시 독립 스케일링 가능
   - SMS, 카카오톡 등 채널 추가 용이
   - 알림 기능만 독립 배포 가능

---

## 2. 현재 아키텍처 (2 서버)

```
┌─────────────────────────────────────────────────────────────┐
│                    Nginx Gateway (:80)                       │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌─────────────────────────┐    ┌─────────────────────────┐ │
│  │   Monolith (:8080)      │    │   QR Service (:8082)    │ │
│  ├─────────────────────────┤    ├─────────────────────────┤ │
│  │ - Auth                  │    │ - QR 토큰 생성 (Redis)  │ │
│  │ - User                  │    │ - CPQR 결제 시작        │ │
│  │ - Store                 │    │ - 결제 승인             │ │
│  │ - Wallet                │    │                         │ │
│  │ - Charge                │    │                         │ │
│  │ - Payment               │    │                         │ │
│  │ - Notification ←─────── │ ←──┤ (결제 완료 시 알림 요청)│ │
│  │ - Menu                  │    │                         │ │
│  └──────────┬──────────────┘    └─────────────────────────┘ │
│             │                                                │
│             ▼                                                │
│  ┌─────────────────────────┐    ┌─────────────────────────┐ │
│  │   MySQL (:3306)         │    │   Redis (:6379)         │ │
│  │ - 모든 영구 데이터       │    │ - QR 토큰              │ │
│  │ - Notification 테이블   │    │ - 세션                  │ │
│  └─────────────────────────┘    └─────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. 목표 아키텍처 (3 서버)

```
┌─────────────────────────────────────────────────────────────┐
│                    Nginx Gateway (:80)                       │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌─────────────────────────┐    ┌─────────────────────────┐ │
│  │   Monolith (:8080)      │    │   QR Service (:8082)    │ │
│  ├─────────────────────────┤    ├─────────────────────────┤ │
│  │ - Auth                  │    │ - QR 토큰 생성          │ │
│  │ - User                  │    │ - CPQR 결제 시작        │ │
│  │ - Store                 │    │ - 결제 승인             │ │
│  │ - Wallet                │    │                         │ │
│  │ - Charge                │    │                         │ │
│  │ - Payment               │    │                         │ │
│  │ - Menu                  │    │                         │ │
│  │                         │    │                         │ │
│  │ ❌ Notification 제거    │    │                         │ │
│  └──────────┬──────────────┘    └──────────┬──────────────┘ │
│             │                               │                │
│             │  ┌─────────────────────────┐  │                │
│             │  │ Notification Service    │  │                │
│             └─►│ (:8083)                 │◄─┘                │
│                ├─────────────────────────┤                   │
│                │ - FCM 푸시 알림          │                   │
│                │ - SSE 실시간 알림        │                   │
│                │ - 알림 내역 조회         │                   │
│                └──────────┬──────────────┘                   │
│                           │                                  │
│  ┌─────────────────────────┐    ┌─────────────────────────┐ │
│  │   MySQL (:3306)         │    │   Redis (:6379)         │ │
│  │ - Notification 테이블   │    │ - QR 토큰              │ │
│  │ (Notification Service   │    │ - FCM 토큰 캐시        │ │
│  │  에서도 접근)           │    │                         │ │
│  └─────────────────────────┘    └─────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

---

## 4. 무중단 배포 시나리오

### 전체 프로세스 개요

```
┌─────────────────────────────────────────────────────────────┐
│  Phase 0: 현재 상태                                          │
│  ┌─────────────┐  ┌─────────────┐                           │
│  │  Monolith   │  │ QR Service  │  ← 2개 서버 운영 중        │
│  └─────────────┘  └─────────────┘                           │
└─────────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────┐
│  Phase 1: 신규 서비스 배포 (트래픽 미전환)                   │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │  Monolith   │  │ QR Service  │  │ Notification (Idle) │  │
│  └─────────────┘  └─────────────┘  └─────────────────────┘  │
│                                      ▲                      │
│                                      │ 헬스체크 및 내부 테스트│
└─────────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────┐
│  Phase 2: 모놀리스 코드 수정 배포                            │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │  Monolith   │──│ QR Service  │  │ Notification        │  │
│  │  (v2)       │  └─────────────┘  └─────────────────────┘  │
│  │ NotifClient │────────────────────────────►│             │  │
│  └─────────────┘                             (HTTP 호출)    │
│                                                             │
│  * 모놀리스가 NotificationService 대신 NotificationClient   │
│    를 호출하도록 변경                                        │
└─────────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────┐
│  Phase 3: Nginx 라우팅 전환                                  │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │  Monolith   │  │ QR Service  │  │ Notification        │  │
│  └──────┬──────┘  └──────┬──────┘  └──────────┬──────────┘  │
│         │                │                     │            │
│         ▼                ▼                     ▼            │
│  ┌─────────────────────────────────────────────────────────┐│
│  │                    Nginx Gateway                        ││
│  │  /api/notifications/* ─────────► Notification Service   ││
│  │  /api/qr, /cpqr/*, /payments/* ► QR Service            ││
│  │  /* ───────────────────────────► Monolith              ││
│  └─────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────┐
│  Phase 4: 모놀리스 Notification 코드 제거                    │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │  Monolith   │  │ QR Service  │  │ Notification        │  │
│  │  (v3)       │  └─────────────┘  │ Service             │  │
│  │             │                   └─────────────────────┘  │
│  │ ❌ Notif 삭제│                                            │
│  └─────────────┘                                            │
│                                                             │
│  완료: 3개 서버 체제                                         │
└─────────────────────────────────────────────────────────────┘
```

---

## 5. 상세 구현 단계

### Phase 0: 준비 작업 (D-2)

#### 0.1 Notification Service 프로젝트 생성
```bash
mkdir -p services/notification-service
cd services/notification-service

# Gradle 프로젝트 초기화
gradle init --type java-application
```

#### 0.2 프로젝트 구조
```
services/notification-service/
├── src/main/java/com/ssafy/keeping/notification/
│   ├── NotificationServiceApplication.java
│   ├── config/
│   │   ├── FirebaseConfig.java
│   │   ├── JpaConfig.java
│   │   └── SecurityConfig.java
│   ├── controller/
│   │   ├── NotificationController.java
│   │   └── FcmTokenController.java
│   ├── service/
│   │   ├── NotificationService.java
│   │   └── FcmService.java
│   ├── domain/
│   │   ├── Notification.java
│   │   └── FcmToken.java
│   ├── repository/
│   │   ├── NotificationRepository.java
│   │   └── FcmTokenRepository.java
│   └── dto/
│       ├── NotificationRequest.java
│       ├── NotificationResponse.java
│       └── FcmTokenRequest.java
├── src/main/resources/
│   ├── application.yml
│   └── firebase-service-account.json
├── Dockerfile
└── build.gradle
```

#### 0.3 API 명세 정의
```yaml
# Internal API (서비스 간 통신)
POST /internal/notifications/send
  Request: { customerId, title, body, type, data }
  Response: { success: true }

# Public API (클라이언트용)
GET /api/notifications
  Header: Authorization
  Response: [{ id, title, body, createdAt, isRead }]

POST /api/notifications/fcm-token
  Header: Authorization
  Request: { token, deviceType }
  Response: { success: true }

DELETE /api/notifications/fcm-token
  Header: Authorization
  Request: { token }
  Response: { success: true }

GET /api/notifications/stream  (SSE)
  Header: Authorization
  Response: Server-Sent Events
```

---

### Phase 1: Notification Service 배포 (트래픽 미전환) (D-day)

#### 1.1 docker-compose.msa.yml 수정

```yaml
services:
  # 기존 서비스 유지
  monolith:
    build: .
    ports:
      - "8080:8080"
    # ...

  qr-service:
    build: ./services/qr-service
    ports:
      - "8082:8082"
    # ...

  # 신규 서비스 추가
  notification-service:
    build: ./services/notification-service
    ports:
      - "8083:8083"
    depends_on:
      mysql:
        condition: service_healthy
      redis:
        condition: service_healthy
    environment:
      SPRING_PROFILES_ACTIVE: docker
      MYSQL_HOST: mysql
      MYSQL_PORT: 3306
      MYSQL_DATABASE: keeping
      MYSQL_USERNAME: ${MYSQL_USERNAME}
      MYSQL_PASSWORD: ${MYSQL_PASSWORD}
      REDIS_HOST: redis
      REDIS_PORT: 6379
      FIREBASE_CREDENTIALS: /app/firebase-service-account.json
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8083/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 3
    networks:
      - keeping-network
```

#### 1.2 신규 서비스 배포 (트래픽 미전환)

```bash
# 1. Notification Service 이미지 빌드
docker-compose -f docker-compose.msa.yml build notification-service

# 2. 신규 서비스만 시작 (기존 서비스 영향 없음)
docker-compose -f docker-compose.msa.yml up -d notification-service

# 3. 헬스체크 확인
curl http://localhost:8083/actuator/health
# 예상 응답: {"status":"UP"}

# 4. 내부 테스트 (docker 네트워크 내에서)
docker exec -it keeping-nginx curl http://notification-service:8083/actuator/health
```

#### 1.3 Phase 1 체크리스트
- [ ] Notification Service 빌드 성공
- [ ] 컨테이너 정상 시작
- [ ] 헬스체크 통과
- [ ] MySQL 연결 성공
- [ ] Firebase 인증 성공
- [ ] 내부 API 테스트 통과

---

### Phase 2: 모놀리스 코드 수정 (ACL 패턴 적용) (D+1)

#### 2.1 모놀리스에 NotificationClient 추가

```java
// src/main/java/com/ssafy/keeping/domain/notification/client/NotificationClient.java
@Component
@RequiredArgsConstructor
public class NotificationClient {

    private final RestTemplate restTemplate;

    @Value("${notification.service.url:http://notification-service:8083}")
    private String notificationServiceUrl;

    public void sendNotification(Long customerId, String title, String body,
                                 NotificationType type, Map<String, Object> data) {
        try {
            NotificationRequest request = NotificationRequest.builder()
                    .customerId(customerId)
                    .title(title)
                    .body(body)
                    .type(type.name())
                    .data(data)
                    .build();

            restTemplate.postForEntity(
                    notificationServiceUrl + "/internal/notifications/send",
                    request,
                    Void.class
            );
        } catch (Exception e) {
            // 알림 실패는 로그만 남기고 진행 (non-blocking)
            log.warn("알림 전송 실패 (무시): customerId={}, title={}", customerId, title, e);
        }
    }
}
```

#### 2.2 기존 NotificationService 호출부 수정

```java
// 변경 전: 직접 NotificationService 호출
@RequiredArgsConstructor
public class WalletService {
    private final NotificationService notificationService;

    public void processPayment(...) {
        // 결제 처리
        notificationService.sendPaymentNotification(customerId, amount);
    }
}

// 변경 후: NotificationClient로 교체
@RequiredArgsConstructor
public class WalletService {
    private final NotificationClient notificationClient;

    public void processPayment(...) {
        // 결제 처리
        notificationClient.sendNotification(
            customerId,
            "결제 완료",
            amount + "원이 결제되었습니다.",
            NotificationType.PAYMENT,
            Map.of("amount", amount)
        );
    }
}
```

#### 2.3 롤링 업데이트로 모놀리스 배포

```bash
# 1. 모놀리스 이미지 재빌드
docker-compose -f docker-compose.msa.yml build monolith

# 2. 롤링 업데이트 (무중단)
docker-compose -f docker-compose.msa.yml up -d --no-deps monolith

# 3. 로그 확인
docker logs -f keeping-monolith 2>&1 | grep -i notification

# 4. 테스트: 결제 시 알림 전송 확인
# 모놀리스 → Notification Service 호출 확인
docker logs keeping-notification-service | grep "send"
```

#### 2.4 Phase 2 체크리스트
- [ ] NotificationClient 구현 완료
- [ ] 기존 호출부 전부 수정
- [ ] 단위 테스트 통과
- [ ] 모놀리스 롤링 업데이트 성공
- [ ] 알림 전송 테스트 통과
- [ ] 에러율 모니터링 (0.1% 미만)

---

### Phase 3: Nginx 라우팅 전환 (D+2)

#### 3.1 nginx.conf 수정

```nginx
events {
    worker_connections 1024;
}

http {
    # ... 기존 설정 ...

    # 백엔드 서버 그룹
    upstream monolith {
        server monolith:8080;
    }

    upstream qr-service {
        server qr-service:8082;
    }

    # 신규: Notification Service
    upstream notification-service {
        server notification-service:8083;
    }

    server {
        listen 80;
        server_name localhost;

        # Nginx 헬스체크
        location /health {
            return 200 'OK';
            add_header Content-Type text/plain;
        }

        # ========== Notification Service ==========
        # 알림 목록 조회
        location /api/notifications {
            proxy_pass http://notification-service;

            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
            proxy_set_header Authorization $http_authorization;
            proxy_set_header X-Customer-Id $http_x_customer_id;

            proxy_connect_timeout 10s;
            proxy_read_timeout 30s;
        }

        # SSE 알림 스트림 (긴 연결 유지)
        location /api/notifications/stream {
            proxy_pass http://notification-service;

            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header Authorization $http_authorization;
            proxy_set_header X-Customer-Id $http_x_customer_id;

            # SSE 전용 설정
            proxy_http_version 1.1;
            proxy_set_header Connection "";
            proxy_buffering off;
            proxy_cache off;
            proxy_read_timeout 86400s;  # 24시간 (SSE 유지)
        }

        # FCM 토큰 관리
        location /api/notifications/fcm-token {
            proxy_pass http://notification-service;

            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header Authorization $http_authorization;

            proxy_connect_timeout 10s;
            proxy_read_timeout 30s;
        }

        # ========== QR Service (기존) ==========
        location /api/qr {
            proxy_pass http://qr-service;
            # ...
        }

        # ... 기존 QR 라우팅 유지 ...

        # ========== Monolith (나머지) ==========
        location / {
            proxy_pass http://monolith;
            # ...
        }
    }
}
```

#### 3.2 무중단 Nginx 설정 적용

```bash
# 1. 현재 설정 백업
docker exec keeping-nginx cp /etc/nginx/nginx.conf /etc/nginx/nginx.conf.backup

# 2. 설정 문법 검사
docker exec keeping-nginx nginx -t
# 예상: nginx: configuration file /etc/nginx/nginx.conf test is successful

# 3. 무중단 리로드
docker exec keeping-nginx nginx -s reload

# 4. 라우팅 확인
curl http://localhost/api/notifications -H "Authorization: Bearer xxx"
# 예상: Notification Service 응답

curl http://localhost/api/qr -X POST ...
# 예상: QR Service 응답

curl http://localhost/api/users/me -H "Authorization: Bearer xxx"
# 예상: Monolith 응답
```

#### 3.3 Phase 3 체크리스트
- [ ] nginx.conf 문법 검사 통과
- [ ] 무중단 리로드 성공
- [ ] /api/notifications → Notification Service 라우팅 확인
- [ ] /api/qr → QR Service 라우팅 유지 확인
- [ ] 나머지 → Monolith 라우팅 유지 확인
- [ ] SSE 연결 테스트 통과

---

### Phase 4: 모놀리스 Notification 코드 제거 (D+3)

#### 4.1 삭제 대상 파일

```
src/main/java/com/ssafy/keeping/domain/notification/
├── controller/
│   ├── NotificationController.java      # 삭제
│   └── FcmTokenController.java          # 삭제
├── service/
│   ├── NotificationService.java         # 삭제
│   ├── FcmService.java                  # 삭제
│   └── SseEmitterService.java           # 삭제
├── domain/
│   ├── Notification.java                # 삭제
│   └── FcmToken.java                    # 삭제
├── repository/
│   ├── NotificationRepository.java      # 삭제
│   └── FcmTokenRepository.java          # 삭제
└── dto/
    ├── NotificationRequest.java         # 삭제
    └── NotificationResponse.java        # 삭제

# NotificationClient는 유지 (다른 도메인에서 호출용)
```

#### 4.2 SecurityConfig 수정

```java
// /api/notifications/** 엔드포인트 제거 (더 이상 모놀리스에서 처리 안함)
// 해당 경로는 nginx에서 Notification Service로 라우팅됨
```

#### 4.3 배포 및 검증

```bash
# 1. 모놀리스 재빌드
docker-compose -f docker-compose.msa.yml build monolith

# 2. 롤링 업데이트
docker-compose -f docker-compose.msa.yml up -d --no-deps monolith

# 3. 검증: 모놀리스에서 직접 호출 시 404
curl http://localhost:8080/api/notifications
# 예상: 404 Not Found (정상 - nginx 통해야 함)

# 4. 검증: nginx 통해 정상 라우팅
curl http://localhost/api/notifications -H "Authorization: Bearer xxx"
# 예상: Notification Service 응답 (200 OK)
```

---

## 6. 롤백 계획

### 6.1 Phase별 롤백

| Phase | 롤백 방법 | 소요 시간 |
|-------|----------|----------|
| Phase 1 | Notification Service 컨테이너 중지 | < 1분 |
| Phase 2 | 모놀리스 이전 버전 배포 | < 5분 |
| Phase 3 | nginx.conf.backup 복구 + reload | < 1분 |
| Phase 4 | git checkout + 모놀리스 재배포 | < 10분 |

### 6.2 긴급 롤백 스크립트

```bash
#!/bin/bash
# rollback-notification.sh

echo "=== Notification Service 롤백 시작 ==="

# 1. Nginx 설정 복구
echo "Step 1: Nginx 설정 복구"
docker exec keeping-nginx cp /etc/nginx/nginx.conf.backup /etc/nginx/nginx.conf
docker exec keeping-nginx nginx -s reload

# 2. Notification Service 중지
echo "Step 2: Notification Service 중지"
docker stop keeping-notification-service

# 3. 모놀리스 이전 버전 배포 (git tag 사용)
echo "Step 3: 모놀리스 롤백"
git checkout tags/before-notification-split -- src/main/java/com/ssafy/keeping/domain/notification/
docker-compose -f docker-compose.msa.yml build monolith
docker-compose -f docker-compose.msa.yml up -d --no-deps monolith

# 4. 검증
echo "Step 4: 검증"
curl http://localhost/api/notifications -H "Authorization: Bearer xxx"

echo "=== 롤백 완료 ==="
```

---

## 7. 모니터링 및 알림

### 7.1 핵심 메트릭

```yaml
# Prometheus 메트릭
- notification_send_total{service="notification-service"}
- notification_send_latency_seconds{service="notification-service"}
- notification_send_error_total{service="notification-service"}
- fcm_push_success_total
- fcm_push_failure_total
- sse_connections_active
```

### 7.2 알림 설정

```yaml
# Alertmanager 규칙
groups:
  - name: notification-service-alerts
    rules:
      - alert: NotificationServiceDown
        expr: up{job="notification-service"} == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Notification Service 다운"

      - alert: NotificationHighErrorRate
        expr: rate(notification_send_error_total[5m]) > 0.1
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "알림 전송 오류율 증가"
```

---

## 8. 타임라인

| 일자 | 작업 | 담당 | 서비스 상태 |
|------|------|------|------------|
| D-2 | Notification Service 개발 완료 | Backend | 2개 서버 |
| D-1 | 스테이징 환경 테스트 | Backend | 2개 서버 |
| D-day | Phase 1: 신규 서비스 배포 (Idle) | DevOps | **3개 서버** |
| D+1 AM | Phase 2: 모놀리스 ACL 패턴 적용 | Backend | 3개 서버 |
| D+1 PM | 통합 테스트 | QA | 3개 서버 |
| D+2 AM | Phase 3: Nginx 라우팅 전환 | DevOps | 3개 서버 (트래픽 전환) |
| D+2 PM | 모니터링 및 안정화 | DevOps | 3개 서버 |
| D+3 | Phase 4: 모놀리스 코드 정리 | Backend | 3개 서버 (완료) |

---

## 9. 체크리스트 요약

### 배포 전
- [ ] Notification Service 단위 테스트 통과
- [ ] Notification Service 통합 테스트 통과
- [ ] Firebase 인증 설정 완료
- [ ] Docker 이미지 빌드 성공
- [ ] 환경 변수 설정 완료
- [ ] nginx.conf 백업 완료
- [ ] 롤백 스크립트 준비

### Phase 1 완료 후
- [ ] 헬스체크 정상
- [ ] MySQL 연결 성공
- [ ] Firebase 연결 성공
- [ ] 내부 API 테스트 통과

### Phase 2 완료 후
- [ ] 모놀리스 → Notification Service 호출 성공
- [ ] 기존 알림 기능 정상 동작
- [ ] 에러율 < 0.1%

### Phase 3 완료 후
- [ ] /api/notifications → Notification Service 라우팅
- [ ] SSE 연결 정상
- [ ] 다른 API 영향 없음

### Phase 4 완료 후
- [ ] 모놀리스에서 Notification 코드 제거 완료
- [ ] 전체 기능 테스트 통과
- [ ] 성능 메트릭 정상
