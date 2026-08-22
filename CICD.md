# Keeping Backend — EC2 배포 & CI/CD 실행 기록

> **목적**: qr 서버 분리 전후 성능 비교, Prometheus/Grafana/Brave tracing 체험, QR 결제 에러 시나리오 검증을 위한 테스트 환경. 실제 Kakao OAuth/Toss 결제 연동은 생략하고 `loadtest` 프로필로 인증 우회.

---

## 목차

1. [인프라 구성](#1-인프라-구성)
2. [서버 초기 세팅](#2-서버-초기-세팅-amazon-linux-2023)
3. [시크릿 준비](#3-시크릿-준비)
4. [Docker 이미지 빌드 & 푸시](#4-docker-이미지-빌드--푸시-로컬-pc)
5. [monolith 서버 배포](#5-monolith-서버-배포-1320970-68)
6. [qr 서버 배포](#6-qr-서버-배포-5279240116)
7. [GitHub Actions 자동 배포](#7-github-actions-자동-배포)
8. [이슈 & 해결 기록](#8-이슈--해결-기록)
9. [현재 상태 요약](#9-현재-상태-요약)
10. [함정 & 주의사항](#10-함정--주의사항)
11. [다음 단계](#11-다음-단계)

---

## 1. 인프라 구성

### 서버 2대 (AWS EC2, ap-northeast-2 서울)

| 역할 | 이름 | Public IP | 인스턴스 | 구성 |
|---|---|---|---|---|
| API 진입점 | keeping-monolith | `13.209.70.68` | t3.small | MySQL + Redis + Spring Boot monolith + Nginx |
| QR 결제 전용 | keeping-qr | `52.79.240.116` | t3.small | MySQL + Redis + QR Service |

- OS: **Amazon Linux 2023**
- SSH 유저: `ec2-user` (작업은 `sudo su -` 로 root)
- 작업 디렉토리: `/opt/keeping`
- 키 페어: `keeping-key.pem` (로컬 `C:\Users\bill5\Downloads\`)

### 보안 그룹 (Security Group)

**keeping-monolith**
| 유형 | 포트 | 소스 |
|---|---|---|
| SSH | 22 | 0.0.0.0/0 (GitHub Actions 위해 개방, 운영 전 좁혀야 함) |
| HTTP | 80 | 0.0.0.0/0 |
| HTTPS | 443 | 0.0.0.0/0 |
| Custom TCP | 8080 | 0.0.0.0/0 (qr 서버가 호출) |

**keeping-qr**
| 유형 | 포트 | 소스 |
|---|---|---|
| SSH | 22 | 0.0.0.0/0 |
| HTTP | 80 | 0.0.0.0/0 |
| HTTPS | 443 | 0.0.0.0/0 |
| Custom TCP | 8081 | 0.0.0.0/0 (nginx가 호출) |

> MySQL(3306), Redis(6379) 는 **SG에 절대 노출 금지**. 컨테이너 내부 `expose` 로만.
> qr-service 컨테이너는 기본 8082 포트지만 SG에 맞춰 `SERVER_PORT=8081` 로 오버라이드.

### 네트워크 흐름

```
[클라이언트/k6]
      |
      v HTTP :80
[monolith 서버]  Nginx
      |                \
      | monolith:8080   \ proxy to http://52.79.240.116:8081
      v                  v
 [Spring Boot]        [qr 서버] QR Service :8081
      |                  |
      v internal         v internal (http://13.209.70.68:8080)
 [monolith 서버] ← ------ [qr 서버]
        X-Internal-Auth 헤더
```

---

## 2. 서버 초기 세팅 (Amazon Linux 2023)

양 서버에 SSH 접속 → `sudo su -` 후:

```bash
# Docker 설치
dnf update -y
dnf install -y docker
systemctl enable --now docker
docker --version

# Docker Compose V2 플러그인 수동 설치
mkdir -p /usr/local/lib/docker/cli-plugins
curl -SL https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 \
  -o /usr/local/lib/docker/cli-plugins/docker-compose
chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
docker compose version

# 작업 디렉토리
mkdir -p /opt/keeping
cd /opt/keeping

# ec2-user 에게 docker 그룹 (GitHub Actions SSH 배포용)
usermod -aG docker ec2-user
```

---

## 3. 시크릿 준비

### monolith 서버에서 한 번 생성 → 양쪽 공유

```bash
# JWT 서명키 (양쪽 동일)
openssl rand -base64 64 | tr -d '\n'

# 내부 API 인증 토큰 (양쪽 동일)
openssl rand -base64 32 | tr -d '\n'

# MySQL 비밀번호 (각 서버 개별)
openssl rand -base64 24 | tr -d '/+=' | head -c 20
```

실제 생성된 값은 로컬 메모리(`deploy_ec2_state.md`) 참조. **절대 저장소에 커밋 금지.**

### 공유 원칙

| 값 | monolith 서버 | qr 서버 | 필수? |
|---|---|---|---|
| `JWT_SECRET` | 동일 | 동일 | ○ |
| `INTERNAL_AUTH_TOKEN` | 동일 | 동일 | ○ |
| `MYSQL_ROOT_PASSWORD` | 서버별 개별 | 서버별 개별 | ○ |

---

## 4. Docker 이미지 빌드 & 푸시 (로컬 PC)

### 4-1. Docker Desktop 실행 + Docker Hub 로그인

```powershell
docker login
```

### 4-2. monolith

```powershell
cd C:\keeping\backend\monolith
.\gradlew clean bootJar -x test

cd C:\keeping\backend
docker build -t welikewatermelon/keeping-monolith:latest ./monolith
docker push welikewatermelon/keeping-monolith:latest
```

### 4-3. qr-service

```powershell
cd C:\keeping\backend\qr-service
.\gradlew clean bootJar -x test

cd C:\keeping\backend
docker build -t welikewatermelon/keeping-qr-service:latest ./qr-service
docker push welikewatermelon/keeping-qr-service:latest
```

---

## 5. monolith 서버 배포 (13.209.70.68)

### 5-1. `/opt/keeping/.env`

```env
DOCKER_USERNAME=welikewatermelon

MYSQL_ROOT_PASSWORD=<서버별 개별, openssl rand 로 생성>

JWT_SECRET=<monolith/qr 공유, openssl rand 로 생성>
INTERNAL_AUTH_TOKEN=<monolith/qr 공유, openssl rand 로 생성>

TOSS_SECRET_KEY=test_sk_Gv6LjeKD8aBnMEWAZA0Y3wYxAdXy

AWS_ACCESS_KEY=dummy
AWS_SECRET_KEY=dummy
AWS_REGION=ap-northeast-2
AWS_S3_BUCKET=dummy

FE_BASE_URL=http://13.209.70.68

QR_SERVICE_URL=http://52.79.240.116:8081
QR_WEBHOOK_ENABLED=true

# application-prod.yml 에서 누락되어 있어서 수동 추가 필요
APP_AUTH_REDIRECT_PATH=/oauth/callback
```

```bash
chmod 600 /opt/keeping/.env
```

### 5-2. `/opt/keeping/gateway/nginx.conf`

서버 분리 라우팅:

- `/api/qr`, `/cpqr/*/initiate`, `/payments/*/approve`, `/api/payments/intent/*` → `http://52.79.240.116:8081` (qr 서버)
- 나머지 → `monolith:8080` (내부 컨테이너)
- `/internal/*` → 403
- `/health` → 200 OK
- 프록시 헤더: `Authorization`, `Cookie`, `Idempotency-Key`, `X-Test-User-Id`, `X-Test-Role`, `X-Test-User-Role`

전체 내용: 저장소 `gateway/nginx.conf` 기준 + upstream `qr_service` 를 `52.79.240.116:8081` 로 지정.

### 5-3. `/opt/keeping/docker-compose.yml`

```yaml
services:
  mysql:
    image: mysql:8.0
    container_name: keeping-mysql
    restart: unless-stopped
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
      MYSQL_DATABASE: keeping
      MYSQL_CHARACTER_SET_SERVER: utf8mb4
      MYSQL_COLLATION_SERVER: utf8mb4_unicode_ci
      TZ: Asia/Seoul
    volumes:
      - mysql_data:/var/lib/mysql
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_unicode_ci
      - --default-time-zone=+09:00
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      interval: 5s
      timeout: 5s
      retries: 20
    expose:
      - "3306"
    networks: [keeping-net]

  redis:
    image: redis:7-alpine
    container_name: keeping-redis
    restart: unless-stopped
    volumes:
      - redis_data:/data
    command: redis-server --appendonly yes
    expose:
      - "6379"
    networks: [keeping-net]

  monolith:
    image: welikewatermelon/keeping-monolith:latest
    container_name: keeping-monolith
    restart: unless-stopped
    env_file: .env
    environment:
      SPRING_PROFILES_ACTIVE: prod,loadtest
      SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/keeping?useSSL=false&serverTimezone=Asia/Seoul&characterEncoding=UTF-8&allowPublicKeyRetrieval=true
      SPRING_DATASOURCE_USERNAME: root
      SPRING_DATASOURCE_PASSWORD: ${MYSQL_ROOT_PASSWORD}
      SPRING_DATA_REDIS_HOST: redis
      SPRING_DATA_REDIS_PORT: 6379
      SPRING_JPA_HIBERNATE_DDL_AUTO: ${DDL_AUTO:-validate}
      TZ: Asia/Seoul
    depends_on:
      mysql: { condition: service_healthy }
      redis: { condition: service_started }
    ports:
      - "8080:8080"
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:8080/actuator/health || exit 1"]
      interval: 15s
      timeout: 10s
      retries: 30
      start_period: 90s
    networks: [keeping-net]

  nginx:
    image: nginx:1.27-alpine
    container_name: keeping-nginx
    restart: unless-stopped
    ports:
      - "80:80"
    volumes:
      - ./gateway/nginx.conf:/etc/nginx/nginx.conf:ro
    depends_on:
      - monolith
    networks: [keeping-net]

volumes:
  mysql_data:
  redis_data:

networks:
  keeping-net:
    driver: bridge
```

### 5-4. 첫 기동 (스키마 자동 생성)

```bash
cd /opt/keeping
docker compose pull
DDL_AUTO=update docker compose up -d
docker compose logs -f monolith
```

`Started KeepingApplication in ... seconds` 나오면 성공.

### 5-5. 헬스체크

```bash
curl http://localhost:8080/actuator/health     # 직접
curl http://localhost/actuator/health          # nginx 경유
curl -H "X-Test-User-Id: 1" -H "X-Test-Role: CUSTOMER" \
  http://localhost/loadtest/verify-customer    # loadtest 백도어
```

응답:
- `{"status":"UP", ...}` × 2
- `{"role":"CUSTOMER","status":"ok","userId":1}`

---

## 6. qr 서버 배포 (52.79.240.116)

### 6-1. `/opt/keeping/.env`

```env
DOCKER_USERNAME=welikewatermelon

MYSQL_ROOT_PASSWORD=<qr 서버 전용, monolith 와 다른 값>

# monolith 와 **완전 동일**
JWT_SECRET=<monolith 와 동일>
INTERNAL_AUTH_TOKEN=<monolith 와 동일>

MONOLITH_URL=http://13.209.70.68:8080
CACHE_MODE=WRITE_THROUGH
CACHE_WARMING_ENABLED=true
LOADTEST_BACKDOOR_ENABLED=true
```

### 6-2. `/opt/keeping/mysql/init/01-create-payment-db.sql`

```sql
CREATE DATABASE IF NOT EXISTS payment_service
CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci;
```

### 6-3. `/opt/keeping/docker-compose.yml`

```yaml
services:
  mysql:
    image: mysql:8.0
    container_name: qr-mysql
    restart: unless-stopped
    environment:
      MYSQL_ROOT_PASSWORD: ${MYSQL_ROOT_PASSWORD}
      MYSQL_DATABASE: payment_service
      MYSQL_CHARACTER_SET_SERVER: utf8mb4
      MYSQL_COLLATION_SERVER: utf8mb4_unicode_ci
      TZ: Asia/Seoul
    volumes:
      - mysql_data:/var/lib/mysql
      - ./mysql/init:/docker-entrypoint-initdb.d:ro
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_unicode_ci
      - --default-time-zone=+09:00
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      interval: 5s
      timeout: 5s
      retries: 20
    expose:
      - "3306"
    networks: [qr-net]

  redis:
    image: redis:7-alpine
    container_name: qr-redis
    restart: unless-stopped
    volumes:
      - redis_data:/data
    command: redis-server --appendonly yes
    expose:
      - "6379"
    networks: [qr-net]

  qr-service:
    image: welikewatermelon/keeping-qr-service:latest
    container_name: keeping-qr-service
    restart: unless-stopped
    env_file: .env
    environment:
      SPRING_PROFILES_ACTIVE: docker
      SERVER_PORT: 8081
      DB_URL: jdbc:mysql://mysql:3306/payment_service?useSSL=false&serverTimezone=Asia/Seoul&characterEncoding=UTF-8&allowPublicKeyRetrieval=true
      DB_USERNAME: root
      DB_PASSWORD: ${MYSQL_ROOT_PASSWORD}
      REDIS_HOST: redis
      REDIS_PORT: 6379
      TZ: Asia/Seoul
    depends_on:
      mysql: { condition: service_healthy }
      redis: { condition: service_started }
    ports:
      - "8081:8081"
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:8081/actuator/health || exit 1"]
      interval: 15s
      timeout: 10s
      retries: 30
      start_period: 60s
    networks: [qr-net]

volumes:
  mysql_data:
  redis_data:

networks:
  qr-net:
    driver: bridge
```

> qr-service 의 JPA `ddl-auto=update` 가 `application.yml` 기본값이라 별도 `DDL_AUTO` 오버라이드 불필요.

### 6-4. 기동

```bash
cd /opt/keeping
docker compose pull
docker compose up -d
docker compose logs -f qr-service
```

성공 신호:
```
Started QrServiceApplication in ... seconds
Tomcat started on port 8081
캐시 워밍 완료
```

### 6-5. 헬스체크

```bash
curl http://localhost:8081/actuator/health
curl http://52.79.240.116:8081/actuator/health
```

둘 다 `{"status":"UP", ...}` 확인.

---

## 7. GitHub Actions 자동 배포

### 7-1. 워크플로우 (`.github/workflows/deploy-msa.yml`)

주요 수정 포인트:
- 경로: `~/keeping` → `/opt/keeping`
- 명령: `docker-compose` → `docker compose` (V2)
- 재시작: `up -d` → `up -d --no-deps` (다른 서비스 영향 격리)

두 job (`deploy-monolith`, `deploy-qr-service`) 동시 실행:
1. JDK 21 세팅 → gradle 빌드
2. Docker Hub 로그인 → 이미지 빌드/푸시
3. appleboy/ssh-action 으로 EC2 접속 → `docker compose pull` + `up -d --no-deps`

트리거: `push: branches: [main]` + `workflow_dispatch` (수동 실행)

### 7-2. GitHub Repository Secrets (총 6개)

| Name | Value |
|---|---|
| `DOCKER_USERNAME` | `welikewatermelon` |
| `DOCKER_PASSWORD` | Docker Hub **Access Token** (GitHub OAuth 로그인 사용자는 비밀번호 없음, 토큰 발급 필수) |
| `EC2_HOST_MAIN` | `13.209.70.68` |
| `EC2_HOST_QR` | `52.79.240.116` |
| `EC2_USERNAME` | `ec2-user` |
| `EC2_KEY` | `keeping-key.pem` 전체 내용 (`-----BEGIN ...-----` ~ `-----END ...-----`) |

PowerShell 로 pem 복사:
```powershell
Get-Content "C:\Users\bill5\Downloads\keeping-key.pem" | Set-Clipboard
```

### 7-3. 첫 푸시

```powershell
git add .github/workflows/deploy-msa.yml
git commit -m "ci: fix deploy workflow for AL2023 + /opt/keeping + docker compose v2"
git push origin main
```

→ `https://github.com/sf-KeepingTeam/backend/actions` 에서 녹색 체크 2개 확인.

---

## 8. 이슈 & 해결 기록

### 8-1. `out of memory allocating heap arena map` (docker push 중)

- 증상: Docker credential helper Go 런타임 메모리 부족
- 원인: Docker Desktop 프로세스 누수 + Windows RAM 부족
- 해결:
  1. 시스템 트레이 Docker Desktop 종료 → 작업 관리자에서 `com.docker.*` 프로세스 잔여물 정리
  2. Docker Desktop 재실행
  3. `docker logout` → `docker login` 재로그인

### 8-2. gradle build OOM (qr-service)

- 증상: `There is insufficient memory for the Java Runtime Environment to continue`
- 원인: Docker Desktop + WSL(`vmmemWSL`)이 RAM 대부분 점유
- 해결:
  1. `wsl --shutdown` 또는 Docker Desktop 종료
  2. gradle 빌드 완료 후 Docker Desktop 재실행 → 이미지 빌드/푸시

### 8-3. `Could not resolve placeholder 'app.auth.redirect-path'`

- 증상: monolith 기동 시 `OAuth2SuccessHandler` 빈 생성 실패
- 원인: 코드가 `@Value("${app.auth.redirect-path}")` 참조하는데 `application-prod.yml` / `application.yml` 모두에 해당 프로퍼티 없음 (코드-설정 불일치)
- 해결: `.env` 에 `APP_AUTH_REDIRECT_PATH=/oauth/callback` 추가 (Spring relaxed binding 으로 `app.auth.redirect-path` 에 매핑)

### 8-4. loadtest 백도어 401

- 증상: `curl -H "X-Test-User-Role: CUSTOMER" ...` → 401
- 원인: monolith 의 `LoadTestAuthenticationFilter` 는 `X-Test-Role` 헤더를 읽음 (`X-Test-User-Role` 은 qr-service 전용)
- 해결:
  1. 헤더명 `X-Test-Role` 로 수정
  2. nginx.conf 에 `proxy_set_header X-Test-Role $http_x_test_role;` 추가 (모든 location 블록)
  3. `docker compose restart nginx`

### 8-5. YAML 들여쓰기 깨짐 (nano paste)

- 증상: `nano` 에 paste 한 YAML 을 `docker compose config` 하면 `did not find expected key`
- 원인: nano 자동 인덴트(autoindent) 가 붙여넣은 공백에 추가 들여쓰기 적용
- 해결: **heredoc** (`cat > file <<'EOF' ... EOF`) 방식으로 재작성. 공백 그대로 보존

### 8-6. SSH 포트 내IP 제한 → GitHub Actions 접속 실패

- 증상: GitHub Actions ssh-action step 에서 timeout
- 원인: SG 에 SSH 22 소스가 "내 IP" 로 제한 → GitHub Actions runner 의 동적 IP 가 차단
- 해결 (임시): SSH 22 소스를 `0.0.0.0/0` 으로 개방
- 향후 개선: GitHub Actions IP 대역만 허용하는 규칙, 또는 AWS SSM Session Manager 전환

---

## 9. 현재 상태 요약

| 항목 | 상태 |
|---|---|
| monolith 서버 기동 | ✅ `Up (healthy)` × 4 (mysql, redis, monolith, nginx) |
| qr 서버 기동 | ✅ `Up (healthy)` × 3 (mysql, redis, qr-service) |
| monolith 헬스체크 | ✅ `{"status":"UP"}` (직접 + nginx 경유) |
| qr 헬스체크 | ✅ `{"status":"UP"}` |
| 서버 간 통신 | ✅ JWT_SECRET / INTERNAL_AUTH_TOKEN 공유 확인 |
| loadtest 백도어 | ✅ JWT 없이 `X-Test-User-Id` + `X-Test-Role` 로 인증 우회 |
| GitHub Actions CI/CD | ✅ `git push main` → 자동 빌드 + 이미지 푸시 + EC2 배포 |

### 외부 접근 URL

- `http://13.209.70.68/` (nginx 주 진입점)
- `http://13.209.70.68:8080/actuator/health` (monolith 직접)
- `http://52.79.240.116:8081/actuator/health` (qr 직접)

---

## 10. 함정 & 주의사항

1. **JWT_SECRET 두 서버 동일 필수** — 다르면 QR 인증 전부 401 연쇄.
2. **INTERNAL_AUTH_TOKEN 두 서버 동일 필수** — 기본값 `internal-service-token-12345` 절대 사용 금지.
3. **`ddl-auto=validate` (prod 기본값)** — monolith 첫 기동만 `DDL_AUTO=update` 로 덮어쓰기. 이후 제거.
4. **`SPRING_DATASOURCE_URL` 명시 필수** — `application-prod.yml` 기본값이 `ssafy_fintech_db` (오타) 라 반드시 `.env` / compose 로 오버라이드.
5. **`APP_AUTH_REDIRECT_PATH` `.env` 에 반드시 추가** — 코드-설정 불일치로 누락된 placeholder.
6. **qr-service 포트 8081** — 컨테이너 기본 8082 지만 SG 에 맞춰 `SERVER_PORT=8081` 로 오버라이드. compose `ports: "8081:8081"`.
7. **loadtest 헤더 이름 차이** — monolith: `X-Test-Role`, qr-service: `X-Test-User-Role`. nginx.conf 는 양쪽 다 프록시.
8. **MySQL/Redis 외부 노출 금지** — compose `expose` 만. SG 에도 열지 말 것.
9. **`mysql/init/02-keeping-test-data.sql` 절대 운영 서버에 두지 말 것** — 테스트 계정이 운영 DB 에 생성됨.
10. **Kakao OAuth 키 하드코딩** — `application-prod.yml` 에 client-id/secret 하드코딩됨 (보안 이슈). 환경변수 분리 과제.
11. **SSH 22 현재 0.0.0.0/0 개방** — GitHub Actions 용 임시 조치. 안정화 후 좁혀야 함.
12. **Docker Hub Access Token** — GitHub OAuth 로그인 사용자는 비밀번호가 없으므로 Access Token 발급 필수.
13. **nano 자동 인덴트 주의** — YAML 은 `cat > file <<'EOF'` heredoc 방식으로 저장.
14. **t3.small 메모리 빠듯함** — monolith + MySQL + Redis + Nginx 동시 구동 시 JVM heap 튜닝(`-Xmx512m`) 필요 가능.

---

## 11. 다음 단계

원래 목적(성능 비교 + 모니터링 + QR 에러 시나리오) 으로 진행:

- [ ] **Prometheus + Grafana** 스택 올리기 (`monitoring/` 기존 구성 활용)
  - `/actuator/prometheus` 메트릭 수집
  - Brave tracing traceId/spanId 연동
  - 대시보드로 RPS / 레이턴시 / DB 커넥션 풀 관찰
- [ ] **k6 부하테스트** 실행 (`k6/performance-comparison/02-qr-payment-flow.js`)
  - 분리 전/후 비교 수치 측정
  - 결과를 Grafana 대시보드와 매칭
- [ ] **QR 결제 에러 시나리오** 검증
  - UNCERTAIN 상태 전이 재현
  - `PaymentRecoveryService` 스케줄러 동작 관찰
- [ ] **운영 단계 개선** (선택)
  - SSH 포트 좁히기 (IP 대역 제한 또는 SSM)
  - Kakao OAuth 키 환경변수 분리
  - HTTPS/TLS 적용 (ALB 또는 certbot)
  - Route 53 Private Hosted Zone 으로 서버 간 통신 DNS 화
