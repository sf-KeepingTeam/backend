# MSA 마이그레이션 빠른 시작 가이드

## 목차

| 문서 | 설명 |
|------|------|
| [01-msa-basics.md](./01-msa-basics.md) | MSA 기초 개념 (완전 초보자용) |
| [02-project-structure.md](./02-project-structure.md) | 프로젝트 구조 이해 |
| [03-qr-payment-service.md](./03-qr-payment-service.md) | QR 서비스 코드 설명 |
| [04-nginx-gateway.md](./04-nginx-gateway.md) | Nginx 설정 상세 |
| [05-docker-compose.md](./05-docker-compose.md) | Docker Compose 설정 |
| [06-canary-deployment.md](./06-canary-deployment.md) | Canary 배포 실전 |

---

## 5분 만에 실행하기

### 1. 환경 확인
```bash
# Docker 설치 확인
docker --version
docker compose version
```

### 2. MSA 모드로 실행
```bash
cd C:\keeping\backend

# MSA 구성으로 실행 (Nginx + QR서비스 + 모놀리스)
docker compose -f docker-compose.msa.yml up -d --build
```

### 3. 상태 확인
```bash
# 모든 서비스 상태 확인
docker compose -f docker-compose.msa.yml ps

# 예상 결과:
# NAME                 STATUS          PORTS
# keeping-nginx        Up (healthy)    0.0.0.0:80->80/tcp
# keeping-monolith     Up (healthy)    8080/tcp
# keeping-qr-payment   Up (healthy)    8081/tcp
# keeping-mysql        Up (healthy)    0.0.0.0:3306->3306/tcp
# keeping-redis        Up (healthy)    0.0.0.0:6379->6379/tcp
```

### 4. 테스트
```bash
# Gateway 헬스체크
curl http://localhost/health

# QR API (아직 모놀리스로 감, 0%)
curl http://localhost/api/qr/health

# 모놀리스 직접
curl http://localhost/actuator/health
```

---

## Canary 배포 시작하기

### Phase 1: 5% 트래픽 전환

```bash
# 1. nginx.conf 수정
# gateway/nginx.conf에서:
# split_clients "${request_id}" $qr_backend {
#     5%   qr-payment;   # ← 0%에서 5%로 변경
#     *    monolith;
# }

# 2. Nginx만 리로드 (무중단!)
docker compose -f docker-compose.msa.yml exec nginx nginx -s reload

# 3. 분배 확인
for i in {1..20}; do
  curl -s http://localhost/api/qr/health
  echo ""
done
```

### 문제 발생 시 롤백
```bash
# nginx.conf에서 0%로 복원 후
docker compose -f docker-compose.msa.yml exec nginx nginx -s reload
```

---

## 로그 확인

```bash
# 전체 로그
docker compose -f docker-compose.msa.yml logs -f

# QR 서비스만
docker compose -f docker-compose.msa.yml logs -f qr-payment

# Nginx 접근 로그
docker compose -f docker-compose.msa.yml exec nginx tail -f /var/log/nginx/access.log
```

---

## 부하 테스트

```bash
# QR 서비스 테스트
k6 run -e BASE_URL=http://localhost monitoring/load-tests/scenarios/qr.js

# Mixed Load 테스트 (MSA 효과 확인)
# 터미널 1
k6 run -e BASE_URL=http://localhost monitoring/load-tests/scenarios/wallet.js

# 터미널 2 (동시에)
k6 run -e BASE_URL=http://localhost monitoring/load-tests/scenarios/qr.js
```

**성공 기준**: Wallet 부하 중에도 QR p95 < 100ms

---

## 서비스 중지

```bash
# 중지 (컨테이너 유지)
docker compose -f docker-compose.msa.yml stop

# 중지 + 삭제
docker compose -f docker-compose.msa.yml down

# 완전 삭제 (볼륨 포함)
docker compose -f docker-compose.msa.yml down -v
```

---

## 파일 구조

```
keeping-backend/
├── docs/
│   └── msa-migration/        ← 문서들
│       ├── 00-quick-start.md     (현재 파일)
│       ├── 01-msa-basics.md
│       ├── 02-project-structure.md
│       ├── 03-qr-payment-service.md
│       ├── 04-nginx-gateway.md
│       ├── 05-docker-compose.md
│       └── 06-canary-deployment.md
│
├── services/
│   └── qr-payment-service/   ← 새로 분리한 서비스
│       ├── src/main/java/...
│       ├── build.gradle
│       └── Dockerfile
│
├── gateway/
│   └── nginx.conf            ← API Gateway 설정
│
├── docker-compose.yml        ← 기존 (모놀리스만)
└── docker-compose.msa.yml    ← MSA 버전 (이걸 사용!)
```

---

## 다음 단계

1. **로컬에서 테스트**: `docker compose -f docker-compose.msa.yml up -d`
2. **부하 테스트**: k6로 성능 확인
3. **Canary 시작**: 5% → 25% → 50% → 100%
4. **EC2 배포**: 동일한 방식으로 서버에 배포

---

## 문제 해결

### 컨테이너가 안 뜰 때
```bash
docker compose -f docker-compose.msa.yml logs qr-payment
```

### 포트 충돌
```bash
# 80 포트 사용 중인지 확인
netstat -an | findstr :80
```

### QR 서비스 빌드 실패
```bash
# 직접 빌드 테스트
cd services/qr-payment-service
./gradlew build
```
