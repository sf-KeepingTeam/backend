# Server 2: Monolith Server

## 역할
- 핵심 비즈니스 로직 (고객, 매장, 메뉴, 지갑 등)
- QR Service에 Internal API 제공
- 데이터 변경 시 QR Service로 Webhook Push

## 구성

```
┌─────────────────────────────────────┐
│         Monolith (:8080)            │
│                                     │
│  ┌─────────────┐  ┌─────────────┐  │
│  │   MySQL     │  │   Redis     │  │
│  │   :3306     │  │   :6379     │  │
│  └─────────────┘  └─────────────┘  │
└─────────────────────────────────────┘
         │
         │ Webhook Push
         ▼
    QR Service
```

## 배포

```bash
# 1. Docker 이미지 준비
# (로컬에서 빌드 후 전송 또는 ECR에서 pull)

# 2. .env 파일 설정
cp .env.example .env
vi .env

# 3. 배포
./deploy.sh

# 4. 확인
curl http://localhost:8080/actuator/health
```

## 환경 변수

| 변수 | 설명 | 예시 |
|------|------|------|
| `MYSQL_ROOT_PASSWORD` | MySQL 비밀번호 | `loadtest1234` |
| `JWT_SECRET` | JWT 서명 키 (모든 서버 동일) | `NbPg+8/r...` |
| `INTERNAL_AUTH_TOKEN` | 내부 통신 토큰 | `loadtest-internal-token` |
| `QR_SERVER_IP` | Webhook Push 대상 IP | `10.0.1.3` |

## Internal API 목록

QR Service가 호출하는 내부 API:

| 메서드 | 경로 | 설명 |
|--------|------|------|
| GET | `/internal/stores/{id}` | 매장 조회 |
| GET | `/internal/stores/all` | 전체 매장 조회 (워밍) |
| GET | `/internal/menus/{id}` | 메뉴 조회 |
| POST | `/internal/menus/batch` | 메뉴 일괄 조회 |
| GET | `/internal/menus/all` | 전체 메뉴 조회 (워밍) |
| GET | `/internal/customers/{id}` | 고객 조회 |
| POST | `/internal/wallets/transfer` | 잔액 이체 |

## Chaos 테스트

Circuit Breaker 테스트를 위해 컨테이너 중단:

```bash
# 중단
docker stop loadtest-monolith

# 재시작
docker start loadtest-monolith
```
