# idx_recovery 인덱스 성능 벤치마크 가이드

## 테스트 목적

`idx_recovery` 인덱스가 결제 복구 쿼리 성능에 미치는 영향을 측정합니다.

- **쓰기 테스트**: 인덱스가 INSERT 성능에 미치는 오버헤드 측정
- **읽기 테스트**: 인덱스가 SELECT 성능 개선에 미치는 효과 측정

## 인덱스 정보

```sql
-- idx_recovery 인덱스 (테스트 대상)
CREATE INDEX idx_recovery ON payment_intent(status, expires_at, created_at);

-- 관련 쿼리 (findRecoveryTargets)
SELECT pi FROM PaymentIntent pi
WHERE (pi.status = 'UNCERTAIN'
       OR (pi.status = 'PENDING' AND pi.expiresAt < :now))
  AND pi.createdAt > :since
ORDER BY pi.createdAt ASC;
```

## 사전 준비

### 1. QR Service 실행 (loadtest 모드)

```bash
cd qr-service
./gradlew bootRun --args='--loadtest.backdoor.enabled=true'
```

또는 환경변수로:

```bash
LOADTEST_BACKDOOR_ENABLED=true java -jar qr-service.jar
```

### 2. 테스트 데이터 생성 (선택)

대용량 데이터로 테스트하려면 먼저 벌크 데이터를 생성합니다:

```bash
# 10,000건 PENDING 상태 데이터 생성
curl -X POST http://localhost:8082/loadtest/benchmark/write-bulk \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": 10001,
    "walletId": 40001,
    "storeId": 30001,
    "amount": 10000,
    "status": "PENDING",
    "count": 10000
  }'

# 5,000건 UNCERTAIN 상태 데이터 생성
curl -X POST http://localhost:8082/loadtest/benchmark/write-bulk \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": 10001,
    "walletId": 40001,
    "storeId": 30001,
    "amount": 10000,
    "status": "UNCERTAIN",
    "count": 5000
  }'
```

## 테스트 실행 순서

### Phase 1: 인덱스 있는 상태에서 테스트

```bash
cd monitoring/load-tests

# 쓰기 테스트 (인덱스 O)
k6 run --env SCENARIO=write_with_index --env QR_SERVICE_URL=http://localhost:8082 scenarios/index-benchmark.js

# 읽기 테스트 (인덱스 O)
k6 run --env SCENARIO=read_with_index --env QR_SERVICE_URL=http://localhost:8082 scenarios/index-benchmark.js
```

### Phase 2: 인덱스 삭제

MySQL에 접속하여 인덱스를 삭제합니다:

```sql
-- 현재 인덱스 확인
SHOW INDEX FROM payment_intent;

-- idx_recovery 인덱스 삭제
DROP INDEX idx_recovery ON payment_intent;

-- 삭제 확인
SHOW INDEX FROM payment_intent;
```

### Phase 3: 인덱스 없는 상태에서 테스트

```bash
# 쓰기 테스트 (인덱스 X)
k6 run --env SCENARIO=write_without_index --env QR_SERVICE_URL=http://localhost:8082 scenarios/index-benchmark.js

# 읽기 테스트 (인덱스 X)
k6 run --env SCENARIO=read_without_index --env QR_SERVICE_URL=http://localhost:8082 scenarios/index-benchmark.js
```

### Phase 4: 인덱스 복구

```sql
-- idx_recovery 인덱스 재생성
CREATE INDEX idx_recovery ON payment_intent(status, expires_at, created_at);

-- 생성 확인
SHOW INDEX FROM payment_intent;
```

## 결과 분석

### 주요 메트릭

| 메트릭 | 설명 | 기대 결과 |
|--------|------|----------|
| `write_latency` | INSERT 응답 시간 | 인덱스 있을 때 약간 느림 (인덱스 유지 비용) |
| `read_latency` | SELECT 응답 시간 | 인덱스 있을 때 **훨씬 빠름** |
| `http_req_duration` | 전체 HTTP 요청 시간 | - |
| `write_success` | 쓰기 성공률 | > 99% |
| `read_success` | 읽기 성공률 | > 99% |

### 예상 결과

1. **쓰기 (INSERT)**
   - 인덱스 있음: 약간 느림 (5-20% 오버헤드)
   - 인덱스 없음: 기준선

2. **읽기 (SELECT)**
   - 인덱스 있음: **매우 빠름** (Index Scan)
   - 인덱스 없음: 느림 (Full Table Scan)

## JSON 결과 저장

```bash
# 결과를 JSON으로 저장
k6 run --env SCENARIO=write_with_index \
  --out json=results/write_with_index.json \
  scenarios/index-benchmark.js

k6 run --env SCENARIO=read_with_index \
  --out json=results/read_with_index.json \
  scenarios/index-benchmark.js
```

## 클린업

테스트 후 생성된 벤치마크 데이터 정리:

```bash
# bench- 또는 bulk- 접두사 데이터 삭제
curl -X DELETE "http://localhost:8082/loadtest/benchmark/cleanup?prefix=bench-"
curl -X DELETE "http://localhost:8082/loadtest/benchmark/cleanup?prefix=bulk-"
```

## 혼합 테스트 (선택)

실제 워크로드를 시뮬레이션하려면 혼합 테스트를 실행합니다 (70% 읽기, 30% 쓰기):

```bash
k6 run --env SCENARIO=mixed --env QR_SERVICE_URL=http://localhost:8082 scenarios/index-benchmark.js
```

## 트러블슈팅

### "Benchmark health check failed"

- `loadtest.backdoor.enabled=true` 설정 확인
- QR Service가 실행 중인지 확인
- 포트 번호 확인 (기본: 8082)

### 테스트 데이터가 너무 많아 정리 안 됨

```sql
-- 직접 SQL로 정리
DELETE FROM payment_intent WHERE qr_token_id LIKE 'bench-%';
DELETE FROM payment_intent WHERE qr_token_id LIKE 'bulk-%';
```

### 인덱스 재생성 시 에러

```sql
-- 기존 인덱스가 있으면 먼저 삭제
DROP INDEX IF EXISTS idx_recovery ON payment_intent;
CREATE INDEX idx_recovery ON payment_intent(status, expires_at, created_at);
```
