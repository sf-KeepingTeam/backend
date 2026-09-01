# EC2 서버 분리 실행 가이드

**목표**: QR-Payment 서비스를 별도 EC2에 배포하여 완전한 자원 격리 달성

---

## 전체 구조

```
[Before: 단일 서버]
┌─────────────────────────────────────────┐
│              EC2-B (기존)                │
│  Nginx + QR-Payment + Monolith + MySQL  │
└─────────────────────────────────────────┘

[After: 서버 분리]
┌─────────────────┐         ┌─────────────────┐
│     EC2-A       │         │     EC2-B       │
│   (QR 전용)     │ ──────► │  (모놀리스)     │
│                 │  HTTP   │                 │
│  Nginx          │         │  Nginx          │
│  QR-Payment     │         │  Monolith       │
│  Redis          │         │  MySQL + Redis  │
└─────────────────┘         └─────────────────┘
```

---

## Step 1: EC2-A 생성 (QR 전용 서버)

### 1.1 AWS 콘솔 접속

```
1. AWS 콘솔 로그인
2. 서비스 → EC2
3. "인스턴스 시작" 클릭
```

### 1.2 인스턴스 설정

| 항목 | 설정값 |
|------|--------|
| 이름 | `keeping-qr-server` |
| AMI | Amazon Linux 2023 AMI |
| 인스턴스 유형 | `t3.small` (2 vCPU, 2GB RAM) |
| 키 페어 | 기존 키 사용 (`keeping-loadtest-key`) 또는 새로 생성 |

### 1.3 네트워크 설정

```
✅ 퍼블릭 IP 자동 할당: 활성화
✅ VPC: 기본 VPC (EC2-B와 동일한 VPC)
✅ 서브넷: 기본 서브넷
```

**중요**: EC2-B와 같은 VPC에 있어야 Private IP로 통신 가능!

### 1.4 보안 그룹 설정 (새로 생성)

```
보안 그룹 이름: keeping-qr-sg
설명: Security group for QR Payment service

인바운드 규칙:
┌────────────┬──────────┬─────────────┬─────────────────────┐
│ 유형       │ 포트     │ 소스        │ 설명                │
├────────────┼──────────┼─────────────┼─────────────────────┤
│ SSH        │ 22       │ 내 IP       │ SSH 접속            │
│ HTTP       │ 80       │ 0.0.0.0/0   │ 외부 QR API 접근    │
│ Custom TCP │ 8081     │ 0.0.0.0/0   │ QR-Payment 직접접근 │
└────────────┴──────────┴─────────────┴─────────────────────┘
```

### 1.5 스토리지

```
볼륨 크기: 20 GiB (기본값 8GB → 20GB로 변경)
볼륨 유형: gp3
```

### 1.6 인스턴스 시작

```
"인스턴스 시작" 클릭

시작되면 기록해둘 정보:
- 인스턴스 ID: i-xxxxxxxxxxxxxxxxx
- 퍼블릭 IP: x.x.x.x (외부 접속용)
- 프라이빗 IP: 172.31.x.x (EC2-B와 통신용)
```

---

## Step 2: EC2-B 정보 확인

### 2.1 EC2-B Private IP 확인

```
AWS 콘솔 → EC2 → 인스턴스 → EC2-B (모놀리스 서버) 선택
→ "프라이빗 IPv4 주소" 확인

예: 172.31.43.238
```

### 2.2 EC2-B 보안 그룹 ID 확인

```
같은 화면에서:
→ 보안 탭 → 보안 그룹 ID 확인

예: sg-0abc1234def56789
```

**이 두 정보를 메모해두세요!**

```
EC2-B Private IP: ________________
EC2-B 보안 그룹 ID: ________________
```

---

## Step 3: 보안 그룹 설정 (중요!)

### 3.1 EC2-B 보안 그룹에 규칙 추가

```
목적: EC2-A(QR)가 EC2-B(모놀리스)의 8080 포트에 접근할 수 있도록 허용
```

**설정 방법**:

```
1. AWS 콘솔 → EC2 → 보안 그룹
2. EC2-B의 보안 그룹 선택 (sg-0abc1234...)
3. "인바운드 규칙 편집" 클릭
4. "규칙 추가" 클릭
5. 다음과 같이 입력:

┌────────────┬──────────┬──────────────────────┬─────────────────────┐
│ 유형       │ 포트     │ 소스                 │ 설명                │
├────────────┼──────────┼──────────────────────┼─────────────────────┤
│ Custom TCP │ 8080     │ keeping-qr-sg        │ QR에서 Monolith접근 │
│            │          │ (EC2-A 보안그룹 선택)│                     │
└────────────┴──────────┴──────────────────────┴─────────────────────┘

6. "규칙 저장" 클릭
```

### 3.2 소스 선택 방법 (상세)

```
"소스" 입력란에서:
1. 드롭다운 클릭
2. "보안 그룹" 선택
3. "keeping-qr-sg" 검색하여 선택

또는 직접 입력:
sg-xxxxxxxx (EC2-A 보안 그룹 ID)
```

---

## Step 4: EC2-A 환경 설정

### 4.1 SSH 접속

```powershell
# 로컬 PowerShell에서
ssh -i "keeping-loadtest-key.pem" ec2-user@<EC2-A-퍼블릭-IP>
```

### 4.2 Docker 설치

```bash
# 패키지 업데이트
sudo yum update -y

# Docker 설치
sudo yum install -y docker

# Docker 서비스 시작
sudo systemctl start docker
sudo systemctl enable docker

# ec2-user를 docker 그룹에 추가
sudo usermod -aG docker ec2-user

# 변경사항 적용을 위해 재접속
exit
```

### 4.3 재접속 후 Docker 확인

```powershell
# 다시 SSH 접속
ssh -i "keeping-loadtest-key.pem" ec2-user@<EC2-A-퍼블릭-IP>
```

```bash
# Docker 작동 확인
docker --version
docker ps
```

### 4.4 Docker Compose 설치

```bash
# Docker Compose 설치
sudo curl -L "https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose

# 실행 권한 부여
sudo chmod +x /usr/local/bin/docker-compose

# 확인
docker-compose --version
```

---

## Step 5: QR-Payment 서비스 배포

### 5.1 디렉토리 생성

```bash
mkdir -p ~/app/gateway
cd ~/app
```

### 5.2 nginx.conf 생성

```bash
nano ~/app/gateway/nginx.conf
```

아래 내용 붙여넣기:

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

        # 헬스체크
        location /health {
            return 200 'OK';
            add_header Content-Type text/plain;
        }

        # 모든 요청 → QR-Payment
        location / {
            proxy_pass http://qr-payment;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header Authorization $http_authorization;
            proxy_set_header X-LoadTest-Auth $http_x_loadtest_auth;
        }
    }
}
```

저장: `Ctrl+O`, `Enter`, `Ctrl+X`

### 5.3 docker-compose.qr.yml 생성

```bash
nano ~/app/docker-compose.qr.yml
```

아래 내용 붙여넣기 (**EC2-B Private IP를 실제 값으로 변경!**):

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
    image: bill5599/qr-payment-service:latest
    container_name: qr-payment
    ports:
      - "8081:8081"
    environment:
      - SPRING_DATA_REDIS_HOST=redis
      - SPRING_DATA_REDIS_PORT=6379
      - MONOLITH_URL=http://172.31.43.238:8080
      - JWT_SECRET=yourSuperSecretKeyForJwtTokenGeneration123456789
    depends_on:
      - redis
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8081/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 3

  redis:
    image: redis:7-alpine
    container_name: qr-redis
    ports:
      - "6379:6379"
    restart: unless-stopped
```

**⚠️ 중요**: `MONOLITH_URL=http://172.31.43.238:8080` 부분을 실제 EC2-B Private IP로 변경!

저장: `Ctrl+O`, `Enter`, `Ctrl+X`

### 5.4 Docker 이미지 Pull 및 실행

```bash
cd ~/app

# 이미지 다운로드
docker-compose -f docker-compose.qr.yml pull

# 서비스 시작
docker-compose -f docker-compose.qr.yml up -d

# 로그 확인
docker-compose -f docker-compose.qr.yml logs -f
```

### 5.5 서비스 상태 확인

```bash
# 컨테이너 상태
docker ps

# 예상 출력:
# CONTAINER ID   IMAGE                              STATUS
# xxxx           nginx:alpine                       Up (healthy)
# xxxx           bill5599/qr-payment-service        Up (healthy)
# xxxx           redis:7-alpine                     Up
```

---

## Step 6: 통신 테스트

### 6.1 EC2-A 내부 테스트

```bash
# EC2-A에서 실행

# Nginx 헬스체크
curl http://localhost/health

# QR-Payment 헬스체크
curl http://localhost:8081/actuator/health

# 예상 출력: {"status":"UP"}
```

### 6.2 EC2-A → EC2-B 통신 테스트

```bash
# EC2-A에서 모놀리스로 직접 요청 (보안 그룹 테스트)
curl http://172.31.43.238:8080/actuator/health

# 성공 시: {"status":"UP"} 또는 JSON 응답
# 실패 시: Connection refused 또는 timeout
```

**실패하면?**
- 보안 그룹 설정 확인 (Step 3)
- EC2-B Private IP 확인
- EC2-B 모놀리스 서비스 실행 중인지 확인

### 6.3 외부에서 EC2-A 테스트 (로컬 PowerShell)

```powershell
# EC2-A 퍼블릭 IP로 테스트
curl http://<EC2-A-퍼블릭-IP>/health

# 예상: OK
```

---

## Step 7: EC2-B 설정 확인

### 7.1 EC2-B에서 모놀리스 실행 확인

```bash
# EC2-B SSH 접속
ssh -i "keeping-loadtest-key.pem" ec2-user@<EC2-B-퍼블릭-IP>

# 컨테이너 상태 확인
docker ps

# 모놀리스가 8080에서 실행 중이어야 함
```

### 7.2 (선택) EC2-B nginx 설정 - QR 라우팅 제거

서버 분리 테스트에서는 클라이언트가 직접 각 서버로 요청하므로,
EC2-B의 nginx에서 /api/qr 라우팅은 그대로 둬도 됨.

---

## Step 8: 부하 테스트

### 8.1 테스트 시나리오

```
테스트 목표: 서버 분리 시 QR 성능이 Wallet 부하에 영향받지 않음을 증명

테스트 1: QR 단독 (EC2-A)
테스트 2: Mixed Load (EC2-B에 Wallet 부하 + EC2-A에 QR 부하)
```

### 8.2 IP 정보 정리

```
EC2-A (QR 서버) 퍼블릭 IP: ________________
EC2-B (모놀리스) 퍼블릭 IP: ________________ (기존: 3.35.52.188)
```

### 8.3 테스트 1: QR 단독 (로컬 PowerShell)

```powershell
k6 run -e BASE_URL=http://<EC2-A-퍼블릭-IP> C:\keeping\backend\monitoring\load-tests\scenarios\payment.js
```

### 8.4 테스트 2: Mixed Load (창 2개)

**창 1 - Wallet 부하 (EC2-B)**:
```powershell
k6 run -e BASE_URL=http://<EC2-B-퍼블릭-IP> C:\keeping\backend\monitoring\load-tests\scenarios\wallet.js
```

**창 2 - QR 부하 (EC2-A)** (창 1 실행 후 바로):
```powershell
k6 run -e BASE_URL=http://<EC2-A-퍼블릭-IP> C:\keeping\backend\monitoring\load-tests\scenarios\payment.js
```

### 8.5 예상 결과

| 테스트 | 단일 서버 | 서버 분리 | 개선 |
|--------|----------|----------|------|
| QR 단독 | 24.72ms | ~20ms | - |
| QR + Wallet 부하 | 88.99ms | **~20ms** | **77%↓** |
| 성능 저하 | 3.6배 | **~1배** | **완전 격리** |

---

## Step 9: 결과 기록

### 테스트 결과 기록표

```
┌─────────────────────────────────────────────────────────────┐
│                    테스트 결과                               │
├─────────────────────────────────────────────────────────────┤
│ 테스트 일시: ____년 __월 __일                                │
│                                                             │
│ [서버 분리 - QR 단독]                                        │
│ QR p(95): ________ ms                                       │
│ QR p(99): ________ ms                                       │
│ 성공률: ________ %                                          │
│                                                             │
│ [서버 분리 - Mixed Load]                                     │
│ QR p(95): ________ ms                                       │
│ QR p(99): ________ ms                                       │
│ 성공률: ________ %                                          │
│                                                             │
│ 성능 저하 배율: ________ 배                                  │
│                                                             │
│ [비교]                                                       │
│ 단일 서버: 3.6배 저하                                        │
│ 서버 분리: ________ 배 저하                                  │
│ 개선율: ________ %                                          │
└─────────────────────────────────────────────────────────────┘
```

---

## 트러블슈팅

### 문제 1: EC2-A → EC2-B 연결 실패

```
증상: curl http://172.31.x.x:8080 timeout 또는 connection refused

해결:
1. EC2-B 보안 그룹에 8080 포트 규칙 추가했는지 확인
2. 소스가 EC2-A 보안 그룹 ID인지 확인
3. EC2-B에서 모놀리스가 8080에서 실행 중인지 확인:
   docker ps | grep monolith
```

### 문제 2: QR-Payment 시작 실패

```
증상: qr-payment 컨테이너가 계속 재시작

해결:
1. 로그 확인:
   docker logs qr-payment

2. Redis 연결 확인:
   docker exec qr-payment ping redis

3. MONOLITH_URL 환경변수 확인:
   docker exec qr-payment env | grep MONOLITH
```

### 문제 3: 외부에서 접근 불가

```
증상: curl http://<EC2-A-퍼블릭-IP> 실패

해결:
1. EC2-A 보안 그룹에 80 포트 열렸는지 확인
2. EC2-A 퍼블릭 IP 맞는지 확인
3. Nginx 실행 중인지 확인:
   docker ps | grep nginx
```

---

## 정리 (테스트 후)

### 비용 절약을 위해 EC2-A 중지

```bash
# AWS 콘솔에서 EC2-A 선택 → 인스턴스 상태 → 인스턴스 중지

# 또는 AWS CLI
aws ec2 stop-instances --instance-ids <EC2-A-인스턴스-ID>
```

**주의**: 중지해도 EBS 스토리지 비용은 발생 (약 $2/월)

### 완전 삭제 (테스트 완료 후)

```
1. EC2-A 인스턴스 종료 (삭제)
2. 보안 그룹 삭제 (keeping-qr-sg)
3. EC2-B 보안 그룹에서 추가한 규칙 제거
```

---

## 체크리스트

### 배포 전
- [ ] EC2-B Private IP 확인
- [ ] EC2-B 보안 그룹 ID 확인
- [ ] EC2-A 생성 (같은 VPC)
- [ ] EC2-A 보안 그룹 생성

### 배포 중
- [ ] EC2-B 보안 그룹에 8080 규칙 추가
- [ ] EC2-A Docker 설치
- [ ] EC2-A Docker Compose 설치
- [ ] nginx.conf 생성
- [ ] docker-compose.qr.yml 생성 (MONOLITH_URL 설정!)
- [ ] 서비스 시작

### 테스트
- [ ] EC2-A 내부 헬스체크
- [ ] EC2-A → EC2-B 통신 테스트
- [ ] 외부에서 EC2-A 접근 테스트
- [ ] QR 단독 부하 테스트
- [ ] Mixed Load 부하 테스트
- [ ] 결과 기록

### 정리
- [ ] 결과 문서화
- [ ] EC2-A 중지 또는 삭제
- [ ] 보안 그룹 정리
