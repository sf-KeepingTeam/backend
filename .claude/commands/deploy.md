---
description: 서버 배포 가이드. EC2에 Docker Compose로 배포하는 전 과정을 안내. 처음 서버를 올리거나 재배포할 때 사용. GitHub Actions CI/CD 설정 포함.
---

# 배포 가이드 (Deploy)

## 현재 아키텍처

```
[GitHub] → [GitHub Actions] → [Docker Hub]
                                    ↓
[EC2-1: monolith + nginx + mysql + redis + prometheus + grafana]
[EC2-2: qr-service]  ← 규모가 작을 땐 EC2-1에 통합 가능
```

## 배포 전 체크리스트

### 1. .env 파일 확인

```bash
# EC2에서
cat .env | grep -E "JWT_SECRET|MYSQL_ROOT_PASSWORD|INTERNAL_AUTH_TOKEN|DOCKER_USERNAME"
```

필수 값:
- `JWT_SECRET` — monolith와 qr-service 동일해야 함
- `INTERNAL_AUTH_TOKEN` — `.env.example`에 누락됨, 직접 추가 필요
- `DOCKER_USERNAME` — Docker Hub 계정
- `MYSQL_ROOT_PASSWORD`

### 2. GitHub Secrets 확인

Repository → Settings → Secrets and variables → Actions:
```
DOCKER_USERNAME       Docker Hub 아이디
DOCKER_PASSWORD       Docker Hub 토큰 (패스워드 아님)
EC2_HOST              EC2 퍼블릭 IP 또는 도메인
EC2_SSH_KEY           EC2 PEM 키 내용 (cat ~/.ssh/key.pem)
EC2_USER              ec2-user 또는 ubuntu
JWT_SECRET            서명 키
MYSQL_ROOT_PASSWORD   DB 비밀번호
INTERNAL_AUTH_TOKEN   내부 서비스 토큰
TOSS_SECRET_KEY       토스 시크릿 키
FE_BASE_URL           프론트 URL
GRAFANA_ADMIN_PASSWORD Grafana 관리자 패스워드
```

### 3. 첫 배포 시 EC2 준비

```bash
# EC2에서 실행
sudo apt-get update && sudo apt-get install -y docker.io docker-compose-plugin
sudo systemctl start docker
sudo usermod -aG docker $USER
# 재접속 후

# 프로젝트 클론
git clone https://github.com/{username}/keeping-backend.git
cd keeping-backend
cp .env.example .env
# .env 값 채우기
nano .env
```

## 배포 방법

### 자동 배포 (GitHub Actions)

`main` 브랜치에 push하면 자동으로:
1. 두 서비스 JAR 빌드
2. Docker 이미지 빌드 & Docker Hub push
3. EC2 SSH → docker compose pull & up

### 수동 배포 (EC2에서 직접)

```bash
cd ~/keeping-backend
git pull

# monolith 이미지만 다시 빌드해서 올릴 때
docker compose -f docker-compose.msa.yml pull
docker compose -f docker-compose.msa.yml up -d --no-deps monolith

# 전체 재기동
docker compose -f docker-compose.msa.yml up -d

# observability (Prometheus + Grafana) 포함
docker compose -f docker-compose.msa.yml --profile observability up -d
```

## 배포 후 검증

```bash
# 서비스 상태 확인
docker compose -f docker-compose.msa.yml ps

# 헬스체크
curl http://localhost:80/actuator/health      # nginx → monolith
curl http://localhost:8082/actuator/health    # qr-service (내부)

# 로그 확인
docker compose -f docker-compose.msa.yml logs -f monolith
docker compose -f docker-compose.msa.yml logs -f qr-service

# Prometheus 메트릭 확인
curl http://localhost:9090/api/v1/query?query=payment_uncertain_count
```

## 롤백

```bash
# 이전 이미지로 롤백
docker compose -f docker-compose.msa.yml stop monolith
docker pull {DOCKER_USERNAME}/keeping-backend:previous-tag
# docker-compose.msa.yml의 image 태그 변경 후
docker compose -f docker-compose.msa.yml up -d monolith
```

## Grafana 대시보드 설정

1. `http://{EC2_IP}:3000` 접속 (admin / {GRAFANA_ADMIN_PASSWORD})
2. Data Source → Prometheus → URL: `http://prometheus:9090`
3. Import dashboard — JSON 파일: `monitoring/grafana-dashboard.json` (있으면)
4. 커스텀 패널 추가:
   - `payment_uncertain_count` — UNCERTAIN 미복구 건수
   - `payment_uncertain_oldest_age_seconds` — 최장 미복구 시간
   - `recovery_result_total` — 복구 결과 by 태그
