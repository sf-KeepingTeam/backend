# QR 결제 서비스 부하 테스트 환경

## 개요

QR 결제 서비스의 3가지 캐시 모드(NONE/PULL/PUSH)별 성능을 비교 측정하고,
Circuit Breaker 동작을 검증하기 위한 부하 테스트 환경입니다.

---

## 아키텍처

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         AWS VPC (Private Network)                        │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌──────────────────┐    ┌──────────────────┐    ┌──────────────────┐  │
│  │ Server 1 (Nginx) │    │ Server 2 (Mono)  │    │ Server 3 (QR)    │  │
│  │ Public: 3.x.x.1  │    │ Private: 10.0.1.2│    │ Private: 10.0.1.3│  │
│  │ Private: 10.0.1.1│    │                  │    │                  │  │
│  │                  │    │ ┌──────────────┐ │    │ ┌──────────────┐ │  │
│  │  ┌────────────┐  │    │ │  Monolith    │ │    │ │  QR Service  │ │  │
│  │  │   Nginx    │──┼────┼─│  App :8080   │ │    │ │  App :8082   │ │  │
│  │  │   :80      │──┼────┼─┼──────────────┼─┼────┼─│              │ │  │
│  │  └────────────┘  │    │ │  MySQL :3306 │ │    │ │  MySQL :3306 │ │  │
│  │                  │    │ │  Redis :6379 │ │    │ │  Redis :6379 │ │  │
│  │                  │    │ └──────────────┘ │    │ └──────────────┘ │  │
│  └──────────────────┘    └──────────────────┘    └──────────────────┘  │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
         ▲
         │ k6 부하 테스트
         │
    ┌────┴────┐
    │ Local   │  (또는 4번째 EC2)
    │ Laptop  │
    └─────────┘
```

---

## 디렉토리 구조

```
deploy/loadtest/
├── README.md                    # 이 문서
├── server1-nginx/               # Nginx Gateway 서버
│   ├── docker-compose.yml
│   ├── nginx.conf.template
│   ├── .env.example
│   └── deploy.sh
├── server2-monolith/            # Monolith 서버
│   ├── docker-compose.yml
│   ├── .env.example
│   └── deploy.sh
├── server3-qr/                  # QR Service 서버
│   ├── docker-compose.yml
│   ├── .env.example
│   └── deploy.sh
└── k6/                          # k6 부하 테스트 스크립트
    ├── README.md
    ├── common.js
    ├── background-load.js
    ├── qr-payment.js
    ├── run-all-modes.sh
    ├── chaos-test.sh
    └── monitor-cb.sh
```

---

## 캐시 모드 설명

### 모드별 동작 방식

| 모드 | 동작 | 용도 |
|------|------|------|
| **NONE** | 캐시 사용 안함, 항상 모놀리스 직접 호출 | 기준선(Baseline) 측정 |
| **PULL** | Cache-Aside (캐시 미스 시 조회 후 저장) | Pull 방식 성능 측정 |
| **PUSH** | Webhook Push + Cache-Aside Fallback | 현재 구현, 최적 성능 기대 |

### 캐시 모드별 데이터 흐름

#### NONE 모드 (캐시 미사용)
```
QR Service                           Monolith
    │                                    │
    │  GET /internal/stores/{id}         │
    ├───────────────────────────────────>│
    │                                    │
    │  StoreResponse                     │
    │<───────────────────────────────────┤
    │                                    │
```
- 모든 요청이 네트워크를 통해 모놀리스로 전달
- 가장 느린 응답 시간 예상
- Circuit Breaker/Retry 동작 확인에 적합

#### PULL 모드 (Cache-Aside)
```
QR Service          Redis              Monolith
    │                 │                    │
    │  GET store:1    │                    │
    ├────────────────>│                    │
    │  (miss)         │                    │
    │<────────────────┤                    │
    │                                      │
    │  GET /internal/stores/1              │
    ├─────────────────────────────────────>│
    │  StoreResponse                       │
    │<─────────────────────────────────────┤
    │                 │                    │
    │  SET store:1    │                    │
    ├────────────────>│                    │
    │                 │                    │
```
- 첫 요청: 캐시 미스 → 모놀리스 조회 → 캐시 저장
- 이후 요청: 캐시 히트 → 빠른 응답
- 캐시 워밍 없음

#### PUSH 모드 (Webhook + Fallback)
```
[시작 시 캐시 워밍]
QR Service          Redis              Monolith
    │                 │                    │
    │  GET /internal/stores/all            │
    ├─────────────────────────────────────>│
    │  [Store1, Store2, ...]               │
    │<─────────────────────────────────────┤
    │  MSET store:*   │                    │
    ├────────────────>│                    │
    │                 │                    │

[데이터 변경 시 Webhook Push]
Monolith                            QR Service
    │  POST /webhook/store              │
    ├──────────────────────────────────>│
    │  {storeId: 1, data: {...}}        │
    │                                   │
    │                    Redis          │
    │                      │            │
    │  SET store:1         │<───────────┤
    │                      │            │

[일반 조회]
QR Service          Redis
    │                 │
    │  GET store:1    │
    ├────────────────>│
    │  StoreResponse  │ (항상 캐시 히트)
    │<────────────────┤
```
- 시작 시 전체 데이터 워밍
- 모놀리스 데이터 변경 시 Webhook으로 캐시 갱신
- 거의 모든 요청이 캐시 히트
- 가장 빠른 응답 시간 예상

---

## 코드 구조 (QR Service)

### 캐시 모드 설정

```java
// CacheModeConfig.java
@Configuration
@ConfigurationProperties(prefix = "cache")
public class CacheModeConfig {

    public enum Mode {
        NONE,  // 캐시 미사용
        PULL,  // Cache-Aside
        PUSH   // Webhook Push
    }

    private Mode mode = Mode.PUSH;

    public boolean isCacheEnabled() {
        return mode != Mode.NONE;
    }

    public boolean isPushEnabled() {
        return mode == Mode.PUSH;
    }
}
```

### ACL Client 캐시 분기

```java
// StoreClient.java
public Optional<StoreResponse> getStore(Long storeId) {
    // NONE 모드: 캐시 건너뛰고 직접 호출
    if (!cacheConfig.isCacheEnabled()) {
        return fetchFromMonolithDirect(storeId);
    }

    // PULL/PUSH 모드: 캐시 우선 조회
    Optional<StoreResponse> cached = cacheRepository.findById(storeId);
    if (cached.isPresent()) {
        return cached;
    }

    // Cache Miss → 모놀리스 Fallback
    return fetchFromMonolithAndCache(storeId);
}
```

### 캐시 워밍 (PUSH 모드 전용)

```java
// CacheWarmingService.java
@Async
@EventListener(ApplicationReadyEvent.class)
public void warmCacheOnStartup() {
    // PUSH 모드가 아니면 워밍 건너뜀
    if (!cacheConfig.isPushEnabled()) {
        log.info("캐시 워밍 건너뜀 - 캐시 모드: {}", cacheConfig.getMode());
        return;
    }

    warmStoreCache();
    warmMenuCache();
}
```

---

## 배포 순서

### 1. Docker 이미지 빌드 (로컬)

```bash
cd backend

# Monolith 이미지 빌드
./gradlew bootBuildImage

# QR Service 이미지 빌드
./gradlew :services:qr-service:bootBuildImage
```

### 2. 이미지 전송

```bash
# 방법 1: Docker save/load
docker save keeping-monolith:latest | ssh server2 'docker load'
docker save keeping-qr-service:latest | ssh server3 'docker load'

# 방법 2: ECR 사용 (권장)
aws ecr get-login-password | docker login --username AWS --password-stdin <account>.dkr.ecr.<region>.amazonaws.com
docker tag keeping-monolith:latest <ecr-uri>/keeping-monolith:latest
docker push <ecr-uri>/keeping-monolith:latest
```

### 3. 설정 파일 배포

```bash
# 각 서버에 설정 파일 복사
scp -r deploy/loadtest/server1-nginx/* server1:/app/
scp -r deploy/loadtest/server2-monolith/* server2:/app/
scp -r deploy/loadtest/server3-qr/* server3:/app/
```

### 4. 환경 변수 설정

각 서버에서 `.env.example`을 `.env`로 복사하고 IP 주소 수정:

```bash
# Server 1 (.env)
MONOLITH_IP=10.0.1.2    # Server 2의 Private IP
QR_SERVER_IP=10.0.1.3   # Server 3의 Private IP

# Server 2 (.env)
QR_SERVER_IP=10.0.1.3   # Webhook Push 대상

# Server 3 (.env)
MONOLITH_IP=10.0.1.2    # ACL 호출 대상
CACHE_MODE=PUSH         # 테스트할 캐시 모드
```

### 5. 서비스 시작 (순서 중요!)

```bash
# 1. Server 2 먼저 (Monolith - 데이터 소스)
ssh server2 "cd /app && ./deploy.sh"

# 2. Server 3 (QR Service)
ssh server3 "cd /app && ./deploy.sh PUSH"

# 3. Server 1 마지막 (Nginx Gateway)
ssh server1 "cd /app && ./deploy.sh"
```

---

## 부하 테스트 실행

### 사전 준비

```bash
# k6 설치
brew install k6          # macOS
choco install k6         # Windows
sudo apt install k6      # Linux

# 환경 변수 설정
export NGINX_PUBLIC_IP="3.xxx.xxx.xxx"
export QR_SERVER_SSH="ec2-user@10.0.1.3"
export MONOLITH_SERVER_SSH="ec2-user@10.0.1.2"
```

### 단일 모드 테스트

```bash
cd deploy/loadtest/k6

# QR 결제 테스트
k6 run -e BASE_URL=http://$NGINX_PUBLIC_IP qr-payment.js

# 배경 부하 테스트
k6 run -e BASE_URL=http://$NGINX_PUBLIC_IP background-load.js
```

### 3모드 비교 테스트 (자동화)

```bash
./run-all-modes.sh
```

이 스크립트는:
1. Server 3에서 캐시 모드를 NONE → PULL → PUSH 순서로 변경
2. 각 모드마다 k6 테스트 실행
3. 결과를 `results/` 디렉토리에 저장

### Circuit Breaker Chaos 테스트

```bash
./chaos-test.sh
```

이 스크립트는:
1. 부하 테스트 시작 (백그라운드)
2. 30초 후 모놀리스 컨테이너 중단
3. 60초간 장애 상태 유지 (Circuit Breaker 동작 확인)
4. 모놀리스 재시작
5. 복구 확인

---

## 예상 결과

| 메트릭 | NONE | PULL | PUSH |
|--------|------|------|------|
| Store 조회 p95 | ~100ms | ~20ms (hit) | ~5ms |
| QR 결제 p95 | ~500ms | ~300ms | ~200ms |
| 최대 TPS | ~200 | ~500 | ~1000 |

### 결과 해석

- **NONE vs PULL**: Cache-Aside 패턴의 효과 측정
- **PULL vs PUSH**: Webhook Push + 캐시 워밍의 추가 효과 측정
- **Circuit Breaker**: 모놀리스 장애 시 빠른 실패 및 복구 확인

---

## AWS 보안 그룹 설정

| 서버 | 인바운드 규칙 |
|------|--------------|
| Server 1 (Nginx) | `80/tcp` from `0.0.0.0/0`, `22/tcp` from My IP |
| Server 2 (Monolith) | `8080/tcp` from VPC CIDR, `22/tcp` from My IP |
| Server 3 (QR) | `8082/tcp` from VPC CIDR, `22/tcp` from My IP |

> **중요**: Monolith(8080)와 QR Service(8082)는 VPC 내부에서만 접근 가능

---

## 트러블슈팅

### 캐시 워밍 실패
```
캐시 워밍 실패: Connection refused
```
→ 모놀리스가 먼저 시작되어 있는지 확인

### Circuit Breaker OPEN 상태 지속
```
CircuitBreaker 'storeClient' is OPEN
```
→ `waitDurationInOpenState` (기본 30초) 후 Half-Open으로 전환됨

### k6 토큰 발급 실패
```
Failed to get test token
```
→ QR Service의 `LOADTEST_BACKDOOR_ENABLED=true` 확인

---

## 관련 파일

| 파일 | 설명 |
|------|------|
| `services/qr-service/.../config/CacheModeConfig.java` | 캐시 모드 설정 |
| `services/qr-service/.../acl/StoreClient.java` | Store ACL (캐시 분기) |
| `services/qr-service/.../acl/MenuClient.java` | Menu ACL (캐시 분기) |
| `services/qr-service/.../warming/CacheWarmingService.java` | 캐시 워밍 |
| `services/qr-service/src/main/resources/application.yml` | 캐시 모드 설정 |
