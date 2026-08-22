# Server 3: QR Service

## 역할
- QR 결제 처리 (생성, 스캔, 승인)
- 모놀리스 데이터 캐싱 (캐시 모드별 동작)
- Circuit Breaker로 장애 격리

## 구성

```
┌─────────────────────────────────────┐
│        QR Service (:8082)           │
│                                     │
│  ┌─────────────┐  ┌─────────────┐  │
│  │   MySQL     │  │   Redis     │  │
│  │   :3306     │  │   :6379     │  │
│  └─────────────┘  └─────────────┘  │
│                                     │
│  Cache Mode: NONE / PULL / PUSH     │
└─────────────────────────────────────┘
         │
         │ ACL 호출
         ▼
    Monolith
```

## 배포

```bash
# 1. Docker 이미지 준비

# 2. .env 파일 설정
cp .env.example .env
vi .env

# 3. 배포 (캐시 모드 지정)
./deploy.sh PUSH   # 또는 NONE, PULL

# 4. 확인
curl http://localhost:8082/actuator/health
```

## 캐시 모드 전환

```bash
# NONE 모드로 전환 (캐시 미사용)
./deploy.sh NONE

# PULL 모드로 전환 (Cache-Aside)
./deploy.sh PULL

# PUSH 모드로 전환 (Webhook + 워밍)
./deploy.sh PUSH
```

## 환경 변수

| 변수 | 설명 | 예시 |
|------|------|------|
| `MYSQL_ROOT_PASSWORD` | MySQL 비밀번호 | `loadtest1234` |
| `JWT_SECRET` | JWT 서명 키 | `NbPg+8/r...` |
| `INTERNAL_AUTH_TOKEN` | 내부 통신 토큰 | `loadtest-internal-token` |
| `MONOLITH_IP` | ACL 호출 대상 IP | `10.0.1.2` |
| `CACHE_MODE` | 캐시 모드 | `NONE`, `PULL`, `PUSH` |
| `CACHE_WARMING_ENABLED` | 캐시 워밍 활성화 | `true`, `false` |
| `LOADTEST_BACKDOOR_ENABLED` | 테스트 토큰 발급 | `true` |

## 캐시 모드별 동작

### NONE
- 캐시 사용 안함
- 모든 요청이 모놀리스로 전달
- 기준선 측정용

### PULL
- Cache-Aside 패턴
- 캐시 미스 시 조회 후 저장
- 첫 요청은 느림, 이후 빠름

### PUSH
- 시작 시 전체 데이터 워밍
- Webhook으로 실시간 캐시 갱신
- 거의 모든 요청이 캐시 히트

## Circuit Breaker 상태 확인

```bash
curl http://localhost:8082/actuator/health | jq '.components.circuitBreakers'
```

상태:
- `CLOSED`: 정상 동작
- `OPEN`: 장애 감지, 빠른 실패
- `HALF_OPEN`: 복구 테스트 중
