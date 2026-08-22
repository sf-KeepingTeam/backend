# 06. Canary 배포 실전 가이드

## 배포 전 체크리스트

### 필수 확인 사항
- [ ] QR-Payment 서비스가 빌드됨
- [ ] 모든 컨테이너가 healthy 상태
- [ ] 부하 테스트 환경 준비 (k6)
- [ ] 모니터링 대시보드 열어둠
- [ ] 롤백 명령어 복사해둠

---

## Phase 0: 준비 (트래픽 0%)

### Step 1: 초기 nginx.conf
```nginx
# gateway/nginx.conf

split_clients "${request_id}" $qr_backend {
    0%   qr-payment;    # 0% = 아직 새 서비스 안 씀
    *    monolith;
}
```

### Step 2: 전체 서비스 시작
```bash
# 빌드 + 시작
docker compose up -d --build

# 상태 확인 (모두 healthy?)
docker compose ps
```

### Step 3: 각 서비스 직접 테스트
```bash
# 모놀리스 직접 테스트
docker compose exec nginx curl http://monolith:8080/actuator/health

# QR 서비스 직접 테스트
docker compose exec nginx curl http://qr-payment:8081/actuator/health
```

### Step 4: Gateway 테스트 (아직 0%)
```bash
# 모든 요청이 모놀리스로 감
curl http://localhost/api/qr -X POST \
  -H "Content-Type: application/json" \
  -d '{"walletId": 40001, "mode": "CPQR"}'
```

---

## Phase 1: Canary 시작 (5%)

### Step 1: nginx.conf 수정
```nginx
split_clients "${request_id}" $qr_backend {
    5%   qr-payment;    # ← 5%로 변경!
    *    monolith;
}
```

### Step 2: Nginx 리로드 (무중단!)
```bash
docker compose exec nginx nginx -s reload
# 기존 연결 끊기지 않고 설정만 변경
```

### Step 3: 트래픽 분배 확인
```bash
# 요청 100번 보내서 분배 확인
for i in {1..100}; do
  curl -s http://localhost/api/qr -X POST \
    -H "Content-Type: application/json" \
    -d '{"walletId": 40001, "mode": "CPQR"}' \
    | grep -o '"service":"[^"]*"'
done | sort | uniq -c

# 예상 결과:
#  5 "service":"qr-payment"
# 95 "service":"monolith"
```

### Step 4: 모니터링 (1-2일)

| 확인 항목 | 기준 | 확인 방법 |
|-----------|------|-----------|
| 에러율 | < 1% | 로그에서 5xx 에러 카운트 |
| 응답시간 | p95 < 500ms | k6 테스트 |
| 기능 정상 | QR 생성/조회 성공 | 수동 테스트 |

```bash
# QR 서비스 로그 모니터링
docker compose logs -f qr-payment | grep -E "(ERROR|WARN)"

# 간단한 부하 테스트
k6 run -e BASE_URL=http://localhost monitoring/load-tests/scenarios/qr.js
```

### Step 5: 문제 있으면 롤백!
```bash
# nginx.conf에서 0%로 변경 후
docker compose exec nginx nginx -s reload
```

---

## Phase 2: 확대 (25%)

### Step 1: nginx.conf 수정
```nginx
split_clients "${request_id}" $qr_backend {
    25%  qr-payment;    # ← 25%로 확대
    *    monolith;
}
```

```bash
docker compose exec nginx nginx -s reload
```

### Step 2: 부하 테스트 강화
```bash
# 더 많은 VU로 테스트
k6 run -e BASE_URL=http://localhost \
  -e VUS=50 \
  monitoring/load-tests/scenarios/qr.js
```

### Step 3: 모니터링 (2-3일)

**핵심 체크**:
- 새 서비스의 응답시간이 모놀리스와 비슷한가?
- 에러가 갑자기 늘지 않았나?
- CPU/메모리 사용량은 정상인가?

```bash
# CPU/메모리 확인
docker stats qr-payment monolith
```

---

## Phase 3: 안정화 (50%)

### Step 1: nginx.conf 수정
```nginx
split_clients "${request_id}" $qr_backend {
    50%  qr-payment;    # ← 50%
    *    monolith;
}
```

```bash
docker compose exec nginx nginx -s reload
```

### Step 2: 본격 부하 테스트
```bash
# Mixed Load 테스트 (핵심!)
# QR 서비스가 Wallet 부하에 영향받지 않는지 확인

# 터미널 1: Wallet 부하
k6 run -e BASE_URL=http://localhost \
  -e TARGET_RPS=200 \
  monitoring/load-tests/scenarios/wallet.js &

# 터미널 2: QR 테스트 (동시에)
k6 run -e BASE_URL=http://localhost \
  monitoring/load-tests/scenarios/qr.js
```

**성공 기준**: QR p95 < 100ms (Wallet 부하 중에도!)

모놀리스에서는 756ms였는데, MSA로 분리 후 25ms면 성공!

### Step 3: 모니터링 (2-3일)
이 단계가 가장 중요! 절반의 트래픽이 새 서비스로 가니까

---

## Phase 4: 완료 (100%)

### Step 1: nginx.conf 수정
```nginx
split_clients "${request_id}" $qr_backend {
    *    qr-payment;    # ← 100%! 전부 새 서비스로
}

# 또는 split_clients 제거하고 직접 지정:
location /api/qr {
    proxy_pass http://qr-payment;
}
```

```bash
docker compose exec nginx nginx -s reload
```

### Step 2: 최종 검증
```bash
# 모든 요청이 qr-payment로 가는지 확인
for i in {1..10}; do
  curl -s http://localhost/api/qr -X POST \
    -H "Content-Type: application/json" \
    -d '{"walletId": 40001, "mode": "CPQR"}'
  echo ""
done
```

### Step 3: 성공 기념 부하 테스트
```bash
# 최대 부하 테스트
k6 run -e BASE_URL=http://localhost \
  -e MAX_RPS=500 \
  monitoring/load-tests/scenarios/breakpoint/qr-breakpoint.js
```

---

## 롤백 시나리오

### 상황 1: 에러율 급증
```bash
# 1. 즉시 0%로 롤백
# nginx.conf:
split_clients "${request_id}" $qr_backend {
    0%   qr-payment;
    *    monolith;
}

# 2. 리로드
docker compose exec nginx nginx -s reload

# 3. 로그 확인
docker compose logs qr-payment --since 5m
```

### 상황 2: 응답시간 급증
```bash
# p95 > 1초면 롤백 고려
# 먼저 원인 파악:
# - Redis 연결 문제?
# - 모놀리스 ACL 호출 병목?

# 임시로 비율 낮추기
split_clients "${request_id}" $qr_backend {
    5%   qr-payment;    # 25%에서 5%로 낮춤
    *    monolith;
}
```

### 상황 3: 서비스 다운
```bash
# QR 서비스 컨테이너가 죽으면?
# Nginx가 자동으로 monolith로 failover (설정 필요)

# nginx.conf에 백업 설정 추가:
upstream qr-payment {
    server qr-payment:8081;
    server monolith:8080 backup;  # 백업 서버
}
```

---

## 모니터링 체크리스트

### 매 단계마다 확인

| 항목 | 명령어 | 기준 |
|------|--------|------|
| 컨테이너 상태 | `docker compose ps` | 모두 healthy |
| 에러 로그 | `docker compose logs --since 5m \| grep ERROR` | 0개 |
| 응답시간 | k6 테스트 | p95 < 500ms |
| CPU | `docker stats` | < 80% |
| 메모리 | `docker stats` | < 80% |

### 알람 설정 (선택)

```bash
# 간단한 모니터링 스크립트
while true; do
  # 헬스체크
  if ! curl -sf http://localhost/health > /dev/null; then
    echo "ALERT: Health check failed!"
    # 알림 보내기 (슬랙 등)
  fi

  # 에러 로그 확인
  errors=$(docker compose logs --since 1m 2>&1 | grep -c ERROR)
  if [ $errors -gt 10 ]; then
    echo "ALERT: Too many errors! ($errors)"
  fi

  sleep 60
done
```

---

## 전체 타임라인 예시

| 날짜 | 작업 | 비율 |
|------|------|------|
| Day 1 | 서비스 배포 + 테스트 | 0% |
| Day 2 | Canary 시작 | 5% |
| Day 4 | 확대 | 25% |
| Day 7 | 안정화 | 50% |
| Day 10 | 완료 | 100% |
| Day 14 | 모놀리스에서 QR 코드 제거 | - |

---

## 완료 후 정리

### 모놀리스 정리
```java
// 모놀리스에서 QR 관련 코드 제거 (선택)
// src/main/java/.../domain/payment/qr/ 삭제

// 단, API는 유지하고 QR 서비스로 redirect 가능
// (하위 호환성)
```

### 문서 업데이트
- README.md에 아키텍처 변경 반영
- API 문서 업데이트
- 부하 테스트 결과 기록

### 다음 분리 대상 선정
```
분리 완료: QR-Payment ✅
다음 대상: Wallet? Auth? Store?

부하 테스트로 다음 병목 확인!
```

---

## 마무리

Canary 배포의 핵심:
1. **점진적**: 한 번에 안 바꿈
2. **모니터링**: 항상 지켜봄
3. **롤백 준비**: 언제든 되돌릴 수 있음

이 과정을 통해:
- 서비스 중단 없이 MSA 전환
- 문제 발생 시 영향 최소화
- 점진적으로 신뢰도 확보
