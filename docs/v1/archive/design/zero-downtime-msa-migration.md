# 무중단 MSA 마이그레이션 전략

## 1. 개요

모놀리식 아키텍처에서 MSA(Microservices Architecture)로 전환할 때, 서비스 중단 없이 안전하게 마이그레이션하는 전략을 설명합니다.

### 대상 서비스
- **Before**: Monolith (QR 토큰 생성 + 기타 기능)
- **After**: Monolith + QR Service (QR 토큰 생성 + QR 결제 처리)

---

## 2. 전략 개요: Strangler Fig Pattern

**Strangler Fig Pattern**은 기존 시스템을 점진적으로 새 시스템으로 교체하는 패턴입니다.

```
┌─────────────────────────────────────────────────────────────┐
│  Phase 1: 기존 상태                                          │
│  ┌─────────────────────────────────────────────────────┐    │
│  │         Nginx Gateway                                │    │
│  │    /api/qr ──────────────► Monolith                  │    │
│  │    /cpqr/* ──────────────► Monolith                  │    │
│  │    /payments/* ──────────► Monolith                  │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  Phase 2: 신규 서비스 배포 (트래픽 미전환)                   │
│  ┌─────────────────────────────────────────────────────┐    │
│  │         Nginx Gateway                                │    │
│  │    /api/qr ──────────────► Monolith                  │    │
│  │    /cpqr/* ──────────────► Monolith                  │    │
│  │    /payments/* ──────────► Monolith                  │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                             │
│  QR Service (Idle) - 헬스체크 및 내부 테스트                 │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  Phase 3: Canary 배포 (일부 트래픽 전환)                     │
│  ┌─────────────────────────────────────────────────────┐    │
│  │         Nginx Gateway (weight 기반)                  │    │
│  │    /api/qr ──┬─ 90% ─────► Monolith                  │    │
│  │              └─ 10% ─────► QR Service                │    │
│  │    /cpqr/* ──────────────► QR Service                │    │
│  │    /payments/* ──────────► QR Service                │    │
│  └─────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  Phase 4: 완전 전환                                          │
│  ┌─────────────────────────────────────────────────────┐    │
│  │         Nginx Gateway                                │    │
│  │    /api/qr ──────────────► QR Service                │    │
│  │    /cpqr/* ──────────────► QR Service                │    │
│  │    /payments/* ──────────► QR Service                │    │
│  └─────────────────────────────────────────────────────┘    │
│                                                             │
│  Monolith QR 도메인: 제거 예정                               │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. 단계별 마이그레이션 계획

### Phase 1: 준비 단계 (1-2일)

#### 1.1 QR Service 개발 및 테스트
```bash
# 로컬 테스트
cd services/qr-service
./gradlew test

# Docker 빌드 테스트
docker build -t qr-service:test .
```

#### 1.2 Redis 데이터 호환성 검증
```bash
# Redis 연결 테스트
docker exec -it redis redis-cli PING

# QR 토큰 저장/조회 테스트
curl -X POST http://localhost:8082/api/qr \
  -H "Content-Type: application/json" \
  -d '{"walletId": 1, "mode": "CPQR", "ttlSeconds": 180}'
```

### Phase 2: Soft Launch (2-3일)

#### 2.1 신규 서비스 배포 (트래픽 미전환)
```yaml
# docker-compose.msa.yml에 qr-service 추가
# 단, nginx.conf는 아직 monolith로 라우팅
qr-service:
  build: ./services/qr-service
  environment:
    SPRING_PROFILES_ACTIVE: docker
  healthcheck:
    test: ["CMD", "curl", "-f", "http://localhost:8082/actuator/health"]
```

#### 2.2 내부 트래픽 테스트
```bash
# 내부 네트워크에서 직접 QR Service 호출
docker exec -it keeping-nginx curl http://qr-service:8082/api/qr/health

# 응답 비교 테스트
# Monolith 응답
curl http://localhost/api/qr -d '...'
# QR Service 응답 (직접 호출)
curl http://localhost:8082/api/qr -d '...'
```

### Phase 3: Canary 배포 (3-5일)

#### 3.1 Nginx Weighted Upstream 설정
```nginx
# 10% 트래픽을 QR Service로 전환
upstream qr-backend {
    server monolith:8080 weight=9;
    server qr-service:8082 weight=1;
}

location /api/qr {
    proxy_pass http://qr-backend;
    # ...
}
```

#### 3.2 점진적 비율 증가
| 일차 | Monolith | QR Service | 모니터링 지표 |
|------|----------|------------|--------------|
| 1일차 | 90% | 10% | Error rate < 0.1% |
| 2일차 | 70% | 30% | Latency p99 < 100ms |
| 3일차 | 50% | 50% | Success rate > 99.9% |
| 4일차 | 20% | 80% | Redis 메모리 안정 |
| 5일차 | 0% | 100% | 최종 검증 |

#### 3.3 롤백 트리거 조건
자동 롤백을 위한 조건 설정:
- Error rate > 1%
- Latency p99 > 500ms
- Redis 연결 실패
- 헬스체크 연속 3회 실패

### Phase 4: 완전 전환 (1일)

#### 4.1 Nginx 설정 최종 변경
```nginx
# 100% QR Service로 전환
upstream qr-service {
    server qr-service:8082;
}

location /api/qr {
    proxy_pass http://qr-service;
    # ...
}
```

#### 4.2 Monolith QR 도메인 비활성화
```java
// SecurityConfig.java에서 /api/qr 엔드포인트 비활성화
// 또는 @Deprecated 추가 후 다음 배포에서 제거
```

---

## 4. 롤백 계획

### 4.1 즉시 롤백 (< 1분)
```bash
# nginx.conf 복구
docker exec -it keeping-nginx sh -c "cp /etc/nginx/nginx.conf.backup /etc/nginx/nginx.conf"
docker exec -it keeping-nginx nginx -s reload
```

### 4.2 완전 롤백 (< 5분)
```bash
# 1. nginx 라우팅 복구
sed -i 's/qr-service/monolith/g' gateway/nginx.conf
docker exec -it keeping-nginx nginx -s reload

# 2. qr-service 중지
docker stop keeping-qr-service

# 3. 검증
curl http://localhost/api/qr/health
```

---

## 5. 데이터 마이그레이션 전략

### 5.1 MySQL → Redis 전환 시 고려사항

QR 토큰은 **임시 데이터**이므로 데이터 마이그레이션이 필요 없습니다.

| 시나리오 | 대응 |
|---------|------|
| 전환 시점에 MySQL에 있는 미사용 토큰 | TTL 만료 대기 (최대 5분) |
| 전환 중 생성된 토큰 | Monolith: MySQL, QR Service: Redis 각각 저장 |
| Canary 기간 중 혼용 | 각 서비스가 자체 저장소 사용, 문제 없음 |

### 5.2 Double-Write 패턴 (선택사항)
데이터 일관성이 중요한 경우:
```java
public QrCreateResponse createQrToken(QrCreateRequest request) {
    // 1. Redis에 저장
    qrTokenRepository.save(qrToken);

    // 2. MySQL에도 저장 (Canary 기간 동안)
    try {
        monolithClient.createQrToken(request);
    } catch (Exception e) {
        log.warn("MySQL 동기화 실패 (무시)", e);
    }

    return response;
}
```

---

## 6. 모니터링 및 알림

### 6.1 핵심 메트릭
```yaml
# Prometheus 메트릭 예시
- qr_token_create_total{service="qr-service"}
- qr_token_create_latency_seconds{service="qr-service"}
- redis_connection_errors_total
- payment_intent_create_total
- payment_approve_total
```

### 6.2 알림 설정
```yaml
# Alertmanager 규칙
groups:
  - name: qr-service-alerts
    rules:
      - alert: QrServiceHighErrorRate
        expr: rate(http_requests_total{service="qr-service",status=~"5.."}[5m]) > 0.01
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "QR Service 오류율 증가"

      - alert: RedisConnectionFailure
        expr: redis_connection_errors_total > 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Redis 연결 실패"
```

---

## 7. 체크리스트

### 배포 전 체크리스트
- [ ] QR Service 단위 테스트 통과
- [ ] QR Service 통합 테스트 통과
- [ ] Redis 연결 테스트 완료
- [ ] Docker 이미지 빌드 성공
- [ ] 환경 변수 설정 완료
- [ ] nginx.conf 백업 완료
- [ ] 롤백 스크립트 준비

### Canary 배포 체크리스트
- [ ] 헬스체크 정상
- [ ] 에러율 < 0.1%
- [ ] 지연시간 p99 < 100ms
- [ ] Redis 메모리 사용량 안정
- [ ] 로그에 예외 없음

### 완전 전환 후 체크리스트
- [ ] Monolith QR 엔드포인트 접근 시 404
- [ ] 모든 QR 관련 API가 QR Service로 라우팅
- [ ] MySQL qr_token 테이블 더 이상 증가하지 않음
- [ ] 성능 메트릭 정상

---

## 8. 타임라인 예시

| 일자 | 작업 | 담당자 |
|------|------|--------|
| D-3 | QR Service 개발 완료 및 코드 리뷰 | Backend |
| D-2 | 스테이징 환경 배포 및 테스트 | Backend |
| D-1 | 프로덕션 배포 (트래픽 미전환) | DevOps |
| D-day | Canary 10% 시작 | DevOps |
| D+1 | Canary 30% | DevOps |
| D+2 | Canary 50% | DevOps |
| D+3 | Canary 80% | DevOps |
| D+4 | 100% 전환 | DevOps |
| D+5 | Monolith QR 도메인 제거 | Backend |

---

## 9. 참고 자료

- [Strangler Fig Pattern - Martin Fowler](https://martinfowler.com/bliki/StranglerFigApplication.html)
- [Canary Deployments - Kubernetes](https://kubernetes.io/docs/concepts/cluster-administration/manage-deployment/#canary-deployments)
- [Blue-Green Deployment](https://martinfowler.com/bliki/BlueGreenDeployment.html)
