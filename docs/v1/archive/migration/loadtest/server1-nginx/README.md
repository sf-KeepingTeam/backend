# Server 1: Nginx Gateway

## 역할
- 외부 트래픽 진입점 (Public IP)
- 요청 라우팅: `/api/qr/*` → QR Service, 나머지 → Monolith
- 내부 API (`/internal`) 외부 접근 차단

## 구성

```
┌─────────────────────────────────────┐
│           Nginx (:80)               │
│                                     │
│  /api/qr/*  ───────> QR Service     │
│  /internal  ───────> 403 Forbidden  │
│  /*         ───────> Monolith       │
└─────────────────────────────────────┘
```

## 배포

```bash
# 1. .env 파일 설정
cp .env.example .env
vi .env  # IP 주소 수정

# 2. 배포
./deploy.sh

# 3. 확인
curl http://localhost/health
```

## 환경 변수

| 변수 | 설명 | 예시 |
|------|------|------|
| `MONOLITH_IP` | Monolith 서버 Private IP | `10.0.1.2` |
| `QR_SERVER_IP` | QR Service 서버 Private IP | `10.0.1.3` |

## 로그 확인

```bash
# 접근 로그 (응답 시간 포함)
docker logs -f loadtest-nginx

# 로그 포맷: IP - request_time - upstream_time - status - request
```
