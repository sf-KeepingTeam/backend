# EC2 서버 분리 배포 계획

**목표**: QR-Payment 서비스를 별도 EC2에 배포하여 완전한 자원 격리 달성

---

## 1. 현재 vs 목표 아키텍처

### 1.1 현재 (단일 EC2)
```
                    [EC2 Instance - 단일]
                    ┌─────────────────────────────────────┐
                    │  Nginx (:80)                        │
                    │    ├── /api/qr → QR-Payment (:8081) │
                    │    └── /* → Monolith (:8080)        │
                    │                                     │
                    │  ┌───────────┐  ┌───────────┐      │
                    │  │QR-Payment │  │ Monolith  │      │
                    │  └───────────┘  └───────────┘      │
                    │         │              │            │
                    │         └──────┬───────┘            │
                    │                │                    │
                    │  ┌─────────────┴─────────────┐     │
                    │  │   Redis      MySQL        │     │
                    │  └───────────────────────────┘     │
                    └─────────────────────────────────────┘

문제점:
- CPU/메모리 공유 → Wallet 부하가 QR에 영향
- 성능 저하: MSA 3.6배, 모놀리식 4.2배 (차이 적음)
```

### 1.2 목표 (서버 분리)
```
[EC2-A: QR 전용]                    [EC2-B: 모놀리스]
┌─────────────────────┐             ┌─────────────────────┐
│  Nginx (:80)        │             │  Nginx (:80)        │
│    └── /* → QR-Payment            │    └── /* → Monolith│
│                     │             │                     │
│  ┌───────────────┐  │             │  ┌───────────────┐  │
│  │  QR-Payment   │  │◄── HTTP ───►│  │   Monolith    │  │
│  │    (:8081)    │  │             │  │    (:8080)    │  │
│  └───────────────┘  │             │  └───────────────┘  │
│         │           │             │         │           │
│  ┌──────┴──────┐    │             │  ┌──────┴──────┐    │
│  │ Redis (QR)  │    │             │  │    MySQL    │    │
│  └─────────────┘    │             │  │    Redis    │    │
└─────────────────────┘             └─────────────────────┘

장점:
- 완전한 CPU/메모리 격리
- Wallet 부하 → QR에 영향 없음
- 예상 성능 저하: ~1배 (거의 없음)
```

---

## 2. 서버 구성 계획

### 2.1 EC2 인스턴스 사양

| 서버 | 역할 | 인스턴스 타입 | 예상 비용 |
|------|------|--------------|----------|
| EC2-A | QR-Payment + Redis | t3.small | ~$15/월 |
| EC2-B | Monolith + MySQL + Redis | t3.medium | ~$30/월 |

### 2.2 네트워크 구성

```
[인터넷]
    │
    ▼
[Application Load Balancer] (선택사항)
    │
    ├── /api/qr/* ────► EC2-A (QR-Payment)
    │
    └── /* ───────────► EC2-B (Monolith)
```

**간단한 버전 (ALB 없이)**:
```
[인터넷]
    │
    ├── EC2-A:80 (QR API)     - 별도 도메인/IP
    │
    └── EC2-B:80 (Main API)   - 기존 도메인/IP
```

---

## 3. 단계별 배포 계획

### Step 1: EC2-A (QR 전용 서버) 생성

```bash
# AWS 콘솔에서 EC2 생성
- AMI: Amazon Linux 2023
- 인스턴스 타입: t3.small
- 보안 그룹:
  - 22 (SSH): 내 IP
  - 80 (HTTP): 0.0.0.0/0
  - 8081 (QR-Payment): EC2-B 보안그룹
  - 6379 (Redis): EC2-B 보안그룹 (필요시)
```

### Step 2: EC2-A 환경 설정

```bash
# SSH 접속
ssh -i "keeping-key.pem" ec2-user@<EC2-A-IP>

# Docker 설치
sudo yum update -y
sudo yum install -y docker
sudo systemctl start docker
sudo systemctl enable docker
sudo usermod -aG docker ec2-user

# Docker Compose 설치
sudo curl -L "https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
sudo chmod +x /usr/local/bin/docker-compose

# 재접속 (docker 그룹 적용)
exit
ssh -i "keeping-key.pem" ec2-user@<EC2-A-IP>
```

### Step 3: EC2-A에 QR-Payment 배포

```bash
# 디렉토리 생성
mkdir -p ~/app/gateway
cd ~/app
```

**docker-compose.qr.yml** 생성:
```yaml
services:
  nginx:
    image: nginx:alpine
    container_name: qr-nginx
    ports:
      - "80:80"
    volumes:
      - ./gateway/nginx.conf:/etc/nginx/nginx.conf:ro
    depends_on:
      - qr-payment
    restart: unless-stopped

  qr-payment:
    image: <your-dockerhub>/qr-payment-service:latest
    container_name: qr-payment
    ports:
      - "8081:8081"
    environment:
      - SPRING_PROFILES_ACTIVE=prod
      - SPRING_DATA_REDIS_HOST=redis
      - MONOLITH_BASE_URL=http://<EC2-B-PRIVATE-IP>:8080
    depends_on:
      - redis
    restart: unless-stopped

  redis:
    image: redis:7-alpine
    container_name: qr-redis
    ports:
      - "6379:6379"
    restart: unless-stopped
```

**gateway/nginx.conf** 생성:
```nginx
events {
    worker_connections 1024;
}

http {
    upstream qr-payment {
        server qr-payment:8081;
    }

    server {
        listen 80;

        location /health {
            return 200 'OK';
            add_header Content-Type text/plain;
        }

        location / {
            proxy_pass http://qr-payment;
            proxy_set_header Host $host;
            proxy_set_header Authorization $http_authorization;
            proxy_set_header X-LoadTest-Auth $http_x_loadtest_auth;
            proxy_set_header X-Real-IP $remote_addr;
        }
    }
}
```

### Step 4: EC2-B (모놀리스) 수정

기존 EC2-B의 nginx.conf에서 QR 라우팅 제거:

```nginx
# EC2-B의 nginx.conf
events {
    worker_connections 1024;
}

http {
    upstream monolith {
        server monolith:8080;
    }

    server {
        listen 80;

        location /health {
            return 200 'OK';
            add_header Content-Type text/plain;
        }

        # QR 요청은 더 이상 여기서 처리하지 않음
        # 클라이언트가 직접 EC2-A로 요청

        location / {
            proxy_pass http://monolith;
            proxy_set_header Host $host;
            proxy_set_header Authorization $http_authorization;
            proxy_set_header X-LoadTest-Auth $http_x_loadtest_auth;
        }
    }
}
```

### Step 5: QR-Payment 서비스 설정 변경

QR-Payment에서 Monolith 호출 시 EC2-B 주소 사용:

```yaml
# application-prod.yml
monolith:
  base-url: http://<EC2-B-PRIVATE-IP>:8080
```

또는 환경변수:
```bash
MONOLITH_BASE_URL=http://<EC2-B-PRIVATE-IP>:8080
```

### Step 6: 보안 그룹 설정

**EC2-A 보안 그룹**:
| 유형 | 포트 | 소스 | 설명 |
|------|------|------|------|
| SSH | 22 | 내 IP | 관리용 |
| HTTP | 80 | 0.0.0.0/0 | QR API 접근 |
| Custom TCP | 8081 | EC2-B SG | 모놀리스에서 콜백 |

**EC2-B 보안 그룹**:
| 유형 | 포트 | 소스 | 설명 |
|------|------|------|------|
| SSH | 22 | 내 IP | 관리용 |
| HTTP | 80 | 0.0.0.0/0 | Main API 접근 |
| Custom TCP | 8080 | EC2-A SG | QR에서 Wallet 조회 |

### Step 7: 서비스 시작

**EC2-A**:
```bash
cd ~/app
docker-compose -f docker-compose.qr.yml up -d
docker-compose -f docker-compose.qr.yml logs -f
```

**EC2-B**:
```bash
cd ~/app
docker-compose -f docker-compose.yml restart nginx
```

---

## 4. 부하 테스트 계획

### 4.1 테스트 시나리오

```
테스트 1: QR 단독 (EC2-A)
- EC2-A에만 QR 부하
- 예상: ~20ms

테스트 2: Wallet 부하 중 QR (핵심!)
- EC2-B에 Wallet 부하 (wallet.js)
- 동시에 EC2-A에 QR 부하 (payment.js)
- 예상: ~20ms (변화 없음!)

테스트 3: 모놀리식 비교 (기존 결과 활용)
- 기존 EC2-B 단일 서버 결과와 비교
```

### 4.2 테스트 명령어

```powershell
# 변수 설정
$EC2_A = "http://<EC2-A-IP>"  # QR 전용
$EC2_B = "http://<EC2-B-IP>"  # 모놀리스

# 테스트 1: QR 단독
k6 run -e BASE_URL=$EC2_A payment.js

# 테스트 2: Mixed Load (창 2개)
# 창1: Wallet 부하 → EC2-B
k6 run -e BASE_URL=$EC2_B wallet.js

# 창2: QR 부하 → EC2-A
k6 run -e BASE_URL=$EC2_A payment.js
```

### 4.3 예상 결과

| 테스트 | 단일 서버 | 서버 분리 | 개선 |
|--------|----------|----------|------|
| QR 단독 | 24.72ms | ~20ms | - |
| QR + Wallet 부하 | 88.99ms | **~20ms** | **77% 감소** |
| 성능 저하 | 3.6배 | **~1배** | **완전 격리** |

---

## 5. 비용 분석

### 5.1 서버 비용

| 구성 | 인스턴스 | 월 비용 (서울 리전) |
|------|----------|-------------------|
| 현재 (단일) | t3.medium x 1 | ~$30 |
| 분리 후 | t3.small + t3.medium | ~$45 |
| **추가 비용** | | **~$15/월** |

### 5.2 비용 대비 효과

```
추가 비용: $15/월 = 약 20,000원/월

얻는 것:
✅ QR 성능 완전 격리 (5초 만료 보장)
✅ 장애 격리 (Wallet 장애 → QR 정상)
✅ 독립 스케일링 (QR만 증설 가능)
✅ 독립 배포 (QR 변경 시 모놀리스 무관)

ROI 분석:
- QR 결제 실패로 인한 매출 손실 > $15면 투자 가치 있음
- 월 결제 건수 1,000건, 건당 10,000원 = 월 1천만원
- 0.1% 실패율 감소 = 월 10,000원 추가 매출
```

---

## 6. 롤백 계획

### 문제 발생 시 롤백

```bash
# EC2-B에서 nginx.conf 원복 (QR 라우팅 다시 추가)
cd ~/app
# nginx.conf에 /api/qr → qr-payment 또는 monolith 라우팅 복원
docker-compose restart nginx

# EC2-A 중지
# AWS 콘솔에서 EC2-A 인스턴스 중지
```

---

## 7. 체크리스트

### 배포 전
- [ ] EC2-A 인스턴스 생성
- [ ] 보안 그룹 설정 (양방향 통신 허용)
- [ ] Docker Hub에 최신 이미지 푸시
- [ ] EC2-B Private IP 확인

### 배포 중
- [ ] EC2-A Docker 설치
- [ ] docker-compose.qr.yml 작성
- [ ] nginx.conf 작성
- [ ] 서비스 시작 및 헬스체크
- [ ] EC2-A ↔ EC2-B 통신 테스트

### 배포 후
- [ ] QR 단독 부하 테스트
- [ ] Mixed Load 부하 테스트
- [ ] 결과 문서화
- [ ] 성능 비교 분석

---

## 8. 다음 단계 (선택)

### 8.1 ALB 도입
```
[인터넷] → [ALB] → /api/qr → EC2-A
                 → /* → EC2-B

장점:
- 단일 진입점
- SSL 종료
- 헬스체크 자동화
```

### 8.2 Auto Scaling
```
QR 트래픽 급증 시:
EC2-A → Auto Scaling Group → EC2-A-1, EC2-A-2, ...

장점:
- 트래픽 대응
- 비용 최적화 (필요시만 증설)
```

### 8.3 ECS/EKS 전환
```
컨테이너 오케스트레이션:
- ECS Fargate: 서버리스 컨테이너
- EKS: Kubernetes 기반

장점:
- 자동 스케일링
- 자동 복구
- 리소스 효율화
```

---

## 요약

| 항목 | 내용 |
|------|------|
| **목표** | QR 서비스 완전 격리 |
| **방법** | EC2 서버 분리 (QR 전용 + 모놀리스) |
| **예상 효과** | 성능 저하 3.6배 → ~1배 |
| **추가 비용** | ~$15/월 |
| **소요 시간** | 약 1-2시간 |
