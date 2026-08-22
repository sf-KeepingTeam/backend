# MSA 무중단 전환 전략

## 1. 전환 전략 비교

### 1.1 Big Bang (빅뱅)

```
[Before]                    [After]
┌──────────────┐           ┌─────┐ ┌─────┐ ┌─────┐
│   Monolith   │  ──X──>   │ QR  │ │Wallet│ │Auth │
└──────────────┘           └─────┘ └─────┘ └─────┘
       ↓
   서비스 중단
   (수 시간~수 일)
```

| 장점 | 단점 |
|------|------|
| 한번에 깔끔하게 전환 | **서비스 중단 발생** |
| 구조가 단순 | 롤백 어려움 |
| | 위험도 매우 높음 |

**적합**: 트래픽 없는 개발/테스트 환경
**비적합**: 운영 중인 서비스 ❌

---

### 1.2 Strangler Fig Pattern (교살자 패턴) ⭐ 권장

```
[Phase 1] API Gateway 추가
┌─────────────────────────────────────────┐
│              API Gateway                 │
└─────────────────┬───────────────────────┘
                  │ 100%
                  ▼
          ┌──────────────┐
          │   Monolith   │
          └──────────────┘

[Phase 2] QR 서비스 분리 (10% 트래픽)
┌─────────────────────────────────────────┐
│              API Gateway                 │
└───────┬─────────────────────┬───────────┘
        │ 10%                 │ 90%
        ▼                     ▼
    ┌───────┐         ┌──────────────┐
    │  QR   │         │   Monolith   │
    └───────┘         └──────────────┘

[Phase 3] QR 서비스 100% 전환
┌─────────────────────────────────────────┐
│              API Gateway                 │
└───────┬─────────────────────┬───────────┘
        │ 100%                │ (QR 제외)
        ▼                     ▼
    ┌───────┐         ┌──────────────┐
    │  QR   │         │   Monolith   │
    └───────┘         │  (QR 제거)   │
                      └──────────────┘

[Phase 4] 나머지 서비스 순차 분리
┌─────────────────────────────────────────┐
│              API Gateway                 │
└──┬──────┬──────┬──────┬────────────────┘
   │      │      │      │
   ▼      ▼      ▼      ▼
┌────┐ ┌────┐ ┌────┐ ┌────┐
│ QR │ │Pay │ │Wall│ │Auth│
└────┘ └────┘ └────┘ └────┘
```

| 장점 | 단점 |
|------|------|
| **무중단 전환** | 전환 기간이 김 |
| 점진적 위험 분산 | 복잡도 증가 |
| 쉬운 롤백 | 모놀리스 + MSA 동시 운영 |
| 트래픽 비율 조절 가능 | |

**적합**: 운영 중인 서비스 ✅

---

### 1.3 Blue-Green Deployment

```
┌─────────────────────────────────────────┐
│              Load Balancer               │
└─────────────────┬───────────────────────┘
                  │
        ┌─────────┴─────────┐
        ▼                   ▼
┌──────────────┐    ┌──────────────┐
│    Blue      │    │    Green     │
│  (Monolith)  │    │    (MSA)     │
│   Active     │    │   Standby    │
└──────────────┘    └──────────────┘

전환 시: Load Balancer에서 Green으로 스위칭
롤백 시: 다시 Blue로 스위칭
```

| 장점 | 단점 |
|------|------|
| 즉시 롤백 가능 | 인프라 비용 2배 |
| 무중단 전환 | 데이터 동기화 복잡 |

---

### 1.4 Canary Deployment

```
┌─────────────────────────────────────────┐
│              Load Balancer               │
└─────────────────┬───────────────────────┘
                  │
        ┌─────────┴─────────┐
        │ 95%               │ 5%
        ▼                   ▼
┌──────────────┐    ┌──────────────┐
│   Monolith   │    │     MSA      │
│   (기존)     │    │   (카나리)   │
└──────────────┘    └──────────────┘

점진적으로 5% → 10% → 25% → 50% → 100%
문제 발생 시 즉시 0%로 롤백
```

| 장점 | 단점 |
|------|------|
| 위험 최소화 | 모니터링 필수 |
| 점진적 검증 | 트래픽 라우팅 복잡 |
| 빠른 롤백 | |

---

## 2. 전략 비교 요약

| 전략 | 중단 시간 | 위험도 | 복잡도 | 비용 | 권장 |
|------|----------|--------|--------|------|------|
| Big Bang | 높음 | 매우 높음 | 낮음 | 낮음 | ❌ |
| **Strangler Fig** | **없음** | **낮음** | 중간 | 중간 | ⭐ |
| Blue-Green | 없음 | 낮음 | 중간 | 높음 | ○ |
| Canary | 없음 | 매우 낮음 | 높음 | 중간 | ○ |

---

## 3. Keeping 프로젝트 권장 전략

### 3.1 권장: Strangler Fig + Canary 혼합

```
[이유]
1. 현재 운영 중 → 무중단 필수
2. 결제 서비스 → 안정성 중요
3. 소규모 팀 → 복잡도 최소화
4. 비용 제한 → 인프라 최소화
```

### 3.2 전환 로드맵

```
[Phase 0] 준비 (1-2주)
├── API Gateway 도입 (Kong / AWS ALB / Nginx)
├── 서비스 간 통신 인터페이스 정의
├── 공통 라이브러리 분리
└── 모니터링/로깅 체계 구축

[Phase 1] QR/Payment 분리 (2-3주) ← 최우선
├── QR 서비스 독립 배포
├── 5% → 25% → 50% → 100% 트래픽 전환
├── 모놀리스에서 QR 코드 제거
└── 결과: 결제 안정성 확보

[Phase 2] Wallet 분리 (2-3주)
├── Wallet 서비스 독립 배포
├── 점진적 트래픽 전환
├── 모놀리스에서 Wallet 코드 제거
└── 결과: 부하 격리 완료

[Phase 3] Auth 분리 (1-2주) - 선택
├── 현재 성능 충분
├── 필요시 분리
└── 또는 모놀리스에 유지

[Phase 4] 모놀리스 폐기
├── 남은 기능 정리
├── 모놀리스 서버 종료
└── 완전한 MSA 전환 완료
```

### 3.3 아키텍처 변화

```
[현재 - 모놀리스]

Client → Monolith (QR + Payment + Wallet + Auth)
              ↓
           MySQL + Redis


[Phase 1 - QR 분리]

              ┌─────────────────┐
Client ────→  │   API Gateway   │
              └────────┬────────┘
                       │
         ┌─────────────┼─────────────┐
         ▼             ▼             │
    ┌─────────┐  ┌──────────────┐   │
    │   QR    │  │   Monolith   │   │
    │ Service │  │(Pay+Wallet+  │   │
    └────┬────┘  │    Auth)     │   │
         │       └──────┬───────┘   │
         │              │           │
         └──────┬───────┘           │
                ▼                   │
         ┌──────────────┐          │
         │ MySQL+Redis  │ ←────────┘
         └──────────────┘


[최종 - 완전 MSA]

              ┌─────────────────┐
Client ────→  │   API Gateway   │
              └────────┬────────┘
                       │
    ┌──────────┬───────┼───────┬──────────┐
    ▼          ▼       ▼       ▼          │
┌──────┐  ┌──────┐  ┌──────┐  ┌──────┐   │
│  QR  │  │ Pay  │  │Wallet│  │ Auth │   │
└──┬───┘  └──┬───┘  └──┬───┘  └──┬───┘   │
   │         │         │         │        │
   ▼         ▼         ▼         ▼        │
┌──────┐  ┌──────┐  ┌──────┐  ┌──────┐   │
│QR DB │  │Pay DB│  │Wall  │  │Auth  │   │
│Redis │  │      │  │ DB   │  │Redis │   │
└──────┘  └──────┘  └──────┘  └──────┘   │
```

---

## 4. 구체적 구현 방법

### 4.1 API Gateway 도입

**옵션 A: AWS ALB + Path Routing (권장 - 이미 AWS 사용 중)**

```yaml
# ALB 리스너 규칙
Rules:
  - Path: /api/qr/*
    Target: qr-service-target-group

  - Path: /api/payment/*
    Target: payment-service-target-group

  - Path: /*
    Target: monolith-target-group  # 기본값
```

**옵션 B: Nginx (간단, 저비용)**

```nginx
upstream monolith {
    server monolith:8080;
}

upstream qr-service {
    server qr-service:8080;
}

server {
    listen 80;

    # QR 요청 → QR 서비스
    location /api/qr {
        proxy_pass http://qr-service;
    }

    # 나머지 → 모놀리스
    location / {
        proxy_pass http://monolith;
    }
}
```

**옵션 C: Kong / Spring Cloud Gateway (고급)**

```yaml
# Kong 설정 예시
services:
  - name: qr-service
    url: http://qr-service:8080
    routes:
      - paths: ["/api/qr"]
        strip_path: false

  - name: monolith
    url: http://monolith:8080
    routes:
      - paths: ["/"]
```

### 4.2 트래픽 점진적 전환 (Canary)

```nginx
# Nginx로 가중치 기반 라우팅
upstream qr-canary {
    server qr-service:8080 weight=10;   # 10% 새 서비스
    server monolith:8080 weight=90;      # 90% 기존
}

location /api/qr {
    proxy_pass http://qr-canary;
}
```

### 4.3 데이터베이스 전략

**초기: 공유 DB**
```
QR Service ──┐
             ├──→ MySQL (공유)
Monolith ────┘
```

**최종: 분리 DB**
```
QR Service ──→ QR DB
Monolith ────→ Main DB

※ 데이터 동기화는 이벤트 기반 또는 API 호출
```

### 4.4 서비스 간 통신

**동기 (REST/gRPC)**
```java
// QR Service에서 Wallet 잔액 확인
@FeignClient(name = "wallet-service")
public interface WalletClient {
    @GetMapping("/api/wallets/{walletId}/balance")
    WalletBalance getBalance(@PathVariable Long walletId);
}
```

**비동기 (이벤트 기반)**
```java
// QR 생성 시 이벤트 발행
@Service
public class QrService {
    public QrToken createQr(...) {
        QrToken qr = ...;
        eventPublisher.publish(new QrCreatedEvent(qr));
        return qr;
    }
}
```

---

## 5. 무중단 전환 체크리스트

### 5.1 전환 전

- [ ] API Gateway 설정 완료
- [ ] 새 서비스 배포 완료
- [ ] 헬스체크 엔드포인트 준비
- [ ] 롤백 계획 수립
- [ ] 모니터링 대시보드 준비

### 5.2 전환 중

- [ ] 5% 트래픽 전환
- [ ] 에러율 모니터링 (< 1%)
- [ ] 응답 시간 모니터링 (p95 < 100ms)
- [ ] 25% → 50% → 100% 점진적 증가
- [ ] 문제 발생 시 즉시 롤백

### 5.3 전환 후

- [ ] 모놀리스에서 해당 코드 제거
- [ ] 불필요한 의존성 정리
- [ ] 문서 업데이트
- [ ] 팀 공유

---

## 6. 위험 관리

### 6.1 롤백 시나리오

```
[문제 감지]
에러율 > 1% 또는 p95 > 500ms

[즉시 조치]
1. API Gateway에서 트래픽 0%로 전환
2. 모든 요청 모놀리스로 라우팅
3. 원인 분석 후 수정
4. 재배포 및 재시도
```

### 6.2 데이터 정합성

```
[문제]
QR 서비스와 모놀리스가 동시에 같은 DB 접근

[해결]
1. 트랜잭션 범위 명확히 분리
2. 낙관적 락(Optimistic Lock) 사용
3. 이벤트 소싱으로 동기화
```

---

## 7. Keeping 프로젝트 실행 계획

### 7.1 즉시 실행 가능 (Docker Compose 기반)

```yaml
# docker-compose.msa.yml
version: '3.8'

services:
  # API Gateway (Nginx)
  gateway:
    image: nginx:alpine
    ports:
      - "80:80"
    volumes:
      - ./nginx.conf:/etc/nginx/nginx.conf
    depends_on:
      - monolith
      - qr-service

  # 기존 모놀리스
  monolith:
    image: welikewatermelon/keeping-backend:latest
    environment:
      - SPRING_PROFILES_ACTIVE=prod,loadtest

  # 새로운 QR 서비스
  qr-service:
    image: welikewatermelon/keeping-qr:latest
    environment:
      - SPRING_PROFILES_ACTIVE=prod

  mysql:
    image: mysql:8.0

  redis:
    image: redis:7-alpine
```

### 7.2 권장 일정

| 주차 | 작업 | 산출물 |
|------|------|--------|
| 1주 | API Gateway 설정, QR 서비스 분리 | docker-compose.msa.yml |
| 2주 | QR 서비스 테스트, 5% 트래픽 전환 | 카나리 배포 완료 |
| 3주 | QR 100% 전환, Wallet 분리 시작 | QR 분리 완료 |
| 4주 | Wallet 테스트, 트래픽 전환 | Wallet 분리 완료 |

---

## 8. 결론

### Keeping 프로젝트 권장 사항

```
✅ 전략: Strangler Fig + Canary
✅ 순서: QR → Payment → Wallet → (Auth)
✅ 도구: Nginx + Docker Compose (초기)
        → AWS ALB + ECS (확장 시)
✅ 기간: 4-6주

핵심 원칙:
1. 무중단 전환 (서비스 중단 없음)
2. 점진적 전환 (위험 분산)
3. 언제든 롤백 가능
4. 결제 안정성 최우선
```

---

*Created: 2026-01-30*
*Project: Keeping Backend MSA Migration*
