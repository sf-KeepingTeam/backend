# 07. EC2 MSA 배포 가이드

## 현재 상태
- EC2에 모놀리식 배포됨 (docker-compose로 실행 중)
- 로컬에서 MSA 테스트 완료 (성능 저하 17배 → 9배 확인)

## 목표
- EC2에 MSA 구성 배포
- 동일 환경에서 모놀리식 vs MSA 비교
- 완전 격리 효과 확인

---

## Step 1: QR-Payment 이미지 Docker Hub에 Push

### 로컬에서 실행 (PowerShell)

```powershell
# 1. 이미지 태그 지정
docker tag backend-qr-payment:latest welikewatermelon/qr-payment-service:latest

# 2. Docker Hub 로그인 (이미 되어있으면 스킵)
docker login

# 3. Push
docker push welikewatermelon/qr-payment-service:latest
```

### 확인
```powershell
# Push 완료 확인
docker images | findstr qr-payment
```

---

## Step 2: EC2 인스턴스 시작

### AWS 콘솔에서
1. EC2 대시보드 접속
2. 기존 인스턴스 선택
3. **인스턴스 상태** → **인스턴스 시작**
4. **새 퍼블릭 IP 확인** (변경됨!)

### SSH 접속 (PowerShell)
```powershell
# 새 IP로 접속
ssh -i "keeping-loadtest-key.pem" ec2-user@{새_퍼블릭_IP}
```

---

## Step 3: EC2에서 기존 서비스 중지

### EC2에서 실행
```bash
# 현재 실행 중인 컨테이너 확인
docker ps

# 기존 모놀리식 중지
cd ~/app
docker compose down

# 완전히 중지 확인
docker ps -a
```

---

## Step 4: MSA 파일 업로드

### 방법 A: SCP로 파일 전송 (로컬 PowerShell)

```powershell
# 1. gateway 폴더 생성 및 nginx.conf 전송
scp -i "keeping-loadtest-key.pem" C:\keeping\backend\gateway\nginx.conf ec2-user@{EC2_IP}:~/app/gateway/

# 2. docker-compose.msa.yml 전송
scp -i "keeping-loadtest-key.pem" C:\keeping\backend\docker-compose.msa.yml ec2-user@{EC2_IP}:~/app/

# 3. .env 파일 전송 (있으면)
scp -i "keeping-loadtest-key.pem" C:\keeping\backend\.env ec2-user@{EC2_IP}:~/app/
```

### 방법 B: EC2에서 직접 파일 생성

```bash
# EC2에서 실행
cd ~/app

# gateway 폴더 생성
mkdir -p gateway

# nginx.conf 생성
cat > gateway/nginx.conf << 'EOF'
events {
    worker_connections 1024;
}

http {
    log_format main '$remote_addr - [$time_local] "$request" '
                    '$status $body_bytes_sent '
                    'upstream: $upstream_addr';

    access_log /var/log/nginx/access.log main;
    error_log /var/log/nginx/error.log warn;

    upstream monolith {
        server monolith:8080;
    }

    upstream qr-payment {
        server qr-payment:8081;
    }

    server {
        listen 80;
        server_name localhost;

        location /health {
            return 200 'OK';
            add_header Content-Type text/plain;
        }

        location /api/loadtest {
            proxy_pass http://monolith;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
            proxy_set_header X-LoadTest-Auth $http_x_loadtest_auth;
        }

        # QR API → MSA로 (나중에 monolith로 변경하여 비교 가능)
        location /api/qr {
            proxy_pass http://qr-payment;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
            proxy_set_header Authorization $http_authorization;
            proxy_set_header X-LoadTest-Auth $http_x_loadtest_auth;
            proxy_connect_timeout 10s;
            proxy_read_timeout 30s;
        }

        location / {
            proxy_pass http://monolith;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
            proxy_set_header Authorization $http_authorization;
            proxy_set_header X-LoadTest-Auth $http_x_loadtest_auth;
        }
    }
}
EOF
```

---

## Step 5: docker-compose.msa.yml 생성 (EC2)

```bash
# EC2에서 실행
cat > docker-compose.msa.yml << 'EOF'
version: '3.8'

services:
  nginx:
    image: nginx:alpine
    container_name: keeping-nginx
    restart: unless-stopped
    ports:
      - "80:80"
    volumes:
      - ./gateway/nginx.conf:/etc/nginx/nginx.conf:ro
    depends_on:
      - monolith
      - qr-payment
    networks:
      - keeping-network

  monolith:
    image: welikewatermelon/keeping-backend:latest
    container_name: keeping-monolith
    restart: unless-stopped
    depends_on:
      mysql:
        condition: service_healthy
      redis:
        condition: service_healthy
    ports:
      - "8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: prod,loadtest
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/keeping?useSSL=false&serverTimezone=Asia/Seoul&characterEncoding=UTF-8&allowPublicKeyRetrieval=true
      SPRING_DATASOURCE_USERNAME: root
      SPRING_DATASOURCE_PASSWORD: ${MYSQL_ROOT_PASSWORD:-1234}
      SPRING_DATA_REDIS_HOST: redis
      SPRING_DATA_REDIS_PORT: 6379
      SPRING_JPA_HIBERNATE_DDL_AUTO: update
      APP_AUTH_JWT_SECRET: ${JWT_SECRET}
      PAYMENT_TOSS_SECRET_KEY: ${TOSS_SECRET_KEY:-test_sk_Gv6LjeKD8aBnMEWAZA0Y3wYxAdXy}
      LOADTEST_BACKDOOR_ENABLED: "true"
      JAVA_OPTS: "-Xms512m -Xmx1536m"
    networks:
      - keeping-network
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 60s

  qr-payment:
    image: welikewatermelon/qr-payment-service:latest
    container_name: keeping-qr-payment
    restart: unless-stopped
    depends_on:
      redis:
        condition: service_healthy
    ports:
      - "8081:8081"
    environment:
      SPRING_PROFILES_ACTIVE: docker
      REDIS_HOST: redis
      REDIS_PORT: 6379
      JWT_SECRET: ${JWT_SECRET}
      MONOLITH_URL: http://monolith:8080
    networks:
      - keeping-network
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8081/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 40s

  mysql:
    image: mysql:8.0
    container_name: keeping-mysql
    restart: unless-stopped
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD:-1234}
      MYSQL_DATABASE: ${MYSQL_DATABASE:-keeping}
    ports:
      - "3306:3306"
    volumes:
      - mysql_data:/var/lib/mysql
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_unicode_ci
      - --max_connections=200
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - keeping-network

  redis:
    image: redis:7-alpine
    container_name: keeping-redis
    restart: unless-stopped
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data
    command: redis-server --appendonly yes
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5
    networks:
      - keeping-network

volumes:
  mysql_data:
  redis_data:

networks:
  keeping-network:
    driver: bridge
EOF
```

---

## Step 6: .env 파일 확인/생성 (EC2)

```bash
# 기존 .env 확인
cat .env

# 없으면 생성
cat > .env << 'EOF'
DOCKER_USERNAME=welikewatermelon
MYSQL_ROOT_PASSWORD=1234
MYSQL_DATABASE=keeping
JWT_SECRET=NbPg+8/rCm9yW15pYbbOTXdg1QTPqDcRMA8oauseuOqzrAdkLMcXfmbMLkqt3tZ5HecMd5bnCscx4Iuo2EjnJA==
TOSS_SECRET_KEY=test_sk_Gv6LjeKD8aBnMEWAZA0Y3wYxAdXy
EOF
```

---

## Step 7: MSA 서비스 시작 (EC2)

```bash
# 최신 이미지 Pull
docker compose -f docker-compose.msa.yml pull

# 서비스 시작
docker compose -f docker-compose.msa.yml up -d

# 상태 확인 (모두 healthy 될 때까지 대기)
docker compose -f docker-compose.msa.yml ps

# 로그 확인
docker compose -f docker-compose.msa.yml logs -f
```

---

## Step 8: 테스트 데이터 삽입 (EC2)

```bash
# 기존 init.sql이 있으면
docker exec -i keeping-mysql mysql -uroot -p1234 keeping < init.sql

# 또는 로컬에서 전송 후
docker cp init.sql keeping-mysql:/tmp/
docker exec -it keeping-mysql mysql -uroot -p1234 keeping -e "source /tmp/init.sql"
```

---

## Step 9: 헬스체크 (EC2 또는 로컬)

```bash
# EC2 퍼블릭 IP로 테스트
curl http://{EC2_IP}/health
curl http://{EC2_IP}/actuator/health
curl http://{EC2_IP}:8081/actuator/health
```

---

## Step 10: 부하 테스트 (로컬 PowerShell)

### 10.1 MSA 모드 - QR 단독
```powershell
k6 run -e BASE_URL=http://{EC2_IP} C:\keeping\backend\monitoring\load-tests\scenarios\payment.js
```

### 10.2 MSA 모드 - Mixed Load (창 2개)
```powershell
# 창 1
k6 run -e BASE_URL=http://{EC2_IP} C:\keeping\backend\monitoring\load-tests\scenarios\wallet.js

# 창 2
k6 run -e BASE_URL=http://{EC2_IP} C:\keeping\backend\monitoring\load-tests\scenarios\payment.js
```

---

## Step 11: 모놀리식 모드로 전환 (비교용)

### EC2에서 nginx.conf 수정
```bash
# QR을 모놀리스로 변경
sed -i 's/proxy_pass http:\/\/qr-payment/proxy_pass http:\/\/monolith/' gateway/nginx.conf

# Nginx 재시작
docker compose -f docker-compose.msa.yml restart nginx
```

### 같은 테스트 반복
```powershell
# 모놀리식 - 단독
k6 run -e BASE_URL=http://{EC2_IP} payment.js

# 모놀리식 - Mixed Load
# 창 1: wallet.js
# 창 2: payment.js
```

---

## Step 12: MSA 모드로 복원

```bash
# QR을 다시 qr-payment로
sed -i 's/proxy_pass http:\/\/monolith/proxy_pass http:\/\/qr-payment/' gateway/nginx.conf

# Nginx 재시작
docker compose -f docker-compose.msa.yml restart nginx
```

---

## 예상 결과

| 환경 | QR 단독 | QR + Wallet | 성능 저하 |
|------|---------|-------------|----------|
| EC2 모놀리식 | ~25ms | ~756ms | 30배 |
| **EC2 MSA** | ~25ms | **~50ms** | **2배 이하** |

---

## 트러블슈팅

### 컨테이너 안 뜰 때
```bash
docker compose -f docker-compose.msa.yml logs qr-payment
docker compose -f docker-compose.msa.yml logs monolith
```

### 이미지 없을 때
```bash
docker pull welikewatermelon/qr-payment-service:latest
```

### 포트 충돌
```bash
# 사용 중인 포트 확인
netstat -tlnp | grep -E "(80|8080|8081|3306|6379)"
```

---

## 정리

테스트 완료 후:
```bash
# 서비스 중지
docker compose -f docker-compose.msa.yml down

# 또는 EC2 인스턴스 중지 (비용 절약)
# AWS 콘솔에서 인스턴스 중지
```
