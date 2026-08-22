# QR 결제 성능 비교 테스트

동일한 환경/동일한 부하에서 3가지 상황의 QR 결제 응답속도를 비교합니다.

---

## 3가지 상황

```
상황1: 모놀리식 (서버 1대)
┌─────────────────────────────────────────────────┐
│  Client (k6)                                     │
│    │                                             │
│    ▼                                             │
│  Nginx ──────► Monolith (모든 기능)              │
│                  ├── QR 결제                      │
│                  ├── Wallet                       │
│                  ├── Store/Menu                   │
│                  └── 전부 같은 스레드풀/DB풀 공유  │
└─────────────────────────────────────────────────┘

상황2: MSA, 캐시 없음 (서버 2대)
┌─────────────────────────────────────────────────┐
│  Client (k6)                                     │
│    │                                             │
│    ▼                                             │
│  Nginx ──┬──► QR Service (CACHE_MODE=NONE)       │
│          │      └── Store/Menu 필요할 때마다      │
│          │          모놀리스에 HTTP 호출 ──┐      │
│          │                                │      │
│          └──► Monolith ◄──────────────────┘      │
│                 ├── Wallet                        │
│                 └── Store/Menu                    │
└─────────────────────────────────────────────────┘

상황3: MSA + 캐싱 (서버 2대)
┌─────────────────────────────────────────────────┐
│  Client (k6)                                     │
│    │                                             │
│    ▼                                             │
│  Nginx ──┬──► QR Service (CACHE_MODE=PUSH)       │
│          │      ├── Store/Menu → Redis 캐시 조회  │
│          │      │   (모놀리스 호출 SKIP!)         │
│          │      └── PIN/잔액 → 모놀리스 동기 호출 │
│          │          (보안/정합성상 캐싱 불가) ─┐   │
│          │                                   │   │
│          └──► Monolith ◄─────────────────────┘   │
│                 ├── Wallet                        │
│                 └── Store/Menu                    │
└─────────────────────────────────────────────────┘
```

---

## 파일 구조

| 파일 | 설명 |
|------|------|
| `common.js` | 공통 설정 (서버 주소, 인증 헤더, 테스트 데이터) |
| `01-background-load.js` | 배경 부하: Wallet/Store/Menu API를 반복 호출하여 모놀리스를 바쁘게 만듦 |
| `02-qr-payment-flow.js` | QR 결제 측정: QR생성→Intent→Approve 전체 플로우의 각 단계 시간 측정 |
| `result-template.md` | 결과 기록 템플릿 |

---

## 테스트 실행 방법

### 사전 준비

1. k6 설치: https://k6.io/docs/get-started/installation/
2. EC2 서버 구동 확인 (Nginx, Monolith, QR Service)
3. 테스트 데이터 확인 (Customer 100명, Store 20개, Menu 200개, Wallet 100개)

### 상황1: 모놀리식

**서버 설정 변경** — Nginx에서 QR 요청도 모놀리스로 라우팅:
```bash
# EC2 Nginx 서버에서
# nginx.conf: location /api/qr → proxy_pass http://monolith:8080;
# QR Service 컨테이너 중지
docker stop qr-service
docker exec nginx nginx -s reload
```

**테스트 실행** — 터미널 2개를 동시에 열어서:
```bash
# 터미널 1: 배경 부하 (모놀리스를 바쁘게 만듦)
k6 run -e BASE_URL=http://<NGINX_IP> 01-background-load.js

# 터미널 2: QR 결제 측정 (터미널 1 실행 후 10초 뒤 시작)
k6 run -e BASE_URL=http://<NGINX_IP> 02-qr-payment-flow.js
```

### 상황2: MSA, 캐시 없음

**서버 설정 변경**:
```bash
# QR Service를 NONE 모드로 시작
# docker-compose.qr.yml: CACHE_MODE=NONE
docker start qr-service
# Nginx: location /api/qr → proxy_pass http://qr-service:8082;
docker exec nginx nginx -s reload
```

**테스트 실행** — 위와 동일 (터미널 2개):
```bash
# 터미널 1: 배경 부하
k6 run -e BASE_URL=http://<NGINX_IP> 01-background-load.js

# 터미널 2: QR 결제 측정
k6 run -e BASE_URL=http://<NGINX_IP> 02-qr-payment-flow.js
```

### 상황3: MSA + 캐싱

**서버 설정 변경**:
```bash
# QR Service를 PUSH 모드로 변경
# docker-compose.qr.yml: CACHE_MODE=PUSH
docker restart qr-service
# 30초 대기 (캐시 워밍)
sleep 30
```

**테스트 실행** — 위와 동일 (터미널 2개):
```bash
# 터미널 1: 배경 부하
k6 run -e BASE_URL=http://<NGINX_IP> 01-background-load.js

# 터미널 2: QR 결제 측정
k6 run -e BASE_URL=http://<NGINX_IP> 02-qr-payment-flow.js
```

---

## VU 수/시간 조절

환경변수로 부하 수준을 변경할 수 있습니다:

```bash
# 배경 부하: 100 VU, 10분
k6 run -e BASE_URL=http://<IP> -e BG_VUS=100 -e BG_DURATION=10m 01-background-load.js

# QR 결제: 최대 100 VU, 5분
k6 run -e BASE_URL=http://<IP> -e QR_VUS=100 -e QR_DURATION=5m 02-qr-payment-flow.js
```

---

## Grafana + Prometheus 모니터링

테스트 실행 중 Grafana에서 아래 메트릭을 관찰하세요.

### k6 메트릭을 Prometheus로 전송하기 (선택)

```bash
# k6 xk6-prometheus-remote-write 확장 설치 후:
k6 run --out experimental-prometheus-rw 02-qr-payment-flow.js
```

### 서버 사이드 메트릭 (Grafana에서 확인)

Prometheus가 `/actuator/prometheus` 엔드포인트에서 수집하는 메트릭들:

| 메트릭 | 확인 포인트 | 의미 |
|--------|-----------|------|
| `jvm_memory_used_bytes` | Heap 사용량 변화 | 메모리 부족 여부 |
| `jvm_gc_pause_seconds` | GC Pause 빈도/시간 | GC로 인한 지연 |
| `hikaricp_connections_active` | 활성 DB 커넥션 수 | DB 커넥션풀 포화 여부 |
| `hikaricp_connections_pending` | 대기 중 커넥션 수 | 0이 아니면 병목 |
| `http_server_requests_seconds` | HTTP 요청 처리시간 | 서버 측 응답시간 |
| `system_cpu_usage` | CPU 사용률 | 서버 부하 수준 |

### 상황별 관찰 포인트

| 상황 | 핵심 관찰 |
|------|---------|
| 상황1 (모놀리식) | `hikaricp_connections_pending` > 0 이면 DB 커넥션 경합 발생 |
| 상황2 (MSA, 캐시 없음) | QR Service의 HTTP 요청이 모놀리스 부하에 영향받는지 확인 |
| 상황3 (MSA + 캐싱) | QR Service의 HTTP 요청 수 감소 확인 (캐시 히트) |

---

## 테스트 순서 (중요)

```
1. 상황1 (모놀리식) 테스트
   ↓ 5분 쿨다운
2. 상황2 (MSA, 캐시 없음) 테스트
   ↓ 5분 쿨다운
3. 상황3 (MSA + 캐싱) 테스트
   ↓
4. 결과를 result-template.md에 기록
5. 환경 복구 (CACHE_MODE=PUSH, Nginx 원래 설정)
```

각 테스트 사이에 **5분 쿨다운**을 두어 이전 테스트의 영향을 제거합니다.

---

## 예상 결과

| 상황 | Intent p95 | Approve p95 | 이유 |
|------|-----------|------------|------|
| 모놀리식 | 1000ms+ | 1500ms+ | Wallet 부하가 QR에 직접 영향 |
| MSA (캐시 없음) | 500~1000ms | 800~1200ms | 서버 분리, 하지만 모놀리스 HTTP 호출 필요 |
| MSA + 캐싱 | 100~300ms | 500~800ms | Store/Menu 캐싱으로 모놀리스 호출 SKIP |
