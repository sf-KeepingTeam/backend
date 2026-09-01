# 2026-03-07 테스트 계획

---

## 개요

오늘 진행할 테스트는 두 가지입니다:

1. **캐싱 전략에 따른 부하 테스트** - MSA + 캐시 vs MSA vs 모놀리식 비교
2. **인덱스 성능 테스트** - `idx_recovery` 인덱스의 쓰기/읽기 성능 영향 측정

---

## 테스트 1: 캐싱 전략에 따른 부하 테스트

> 참고: `docs/todolist/qr서버분리및성능최적화todoList.md`

### 목적

Wallet 서비스에 부하가 걸렸을 때, QR 서비스 응답 시간이 얼마나 영향받는지 측정

### 테스트 시나리오

| 순서 | 상황 | 설명 | 환경 설정 |
|------|------|------|-----------|
| 1 | **상황 A** | MSA + Redis 캐시 (현재) | `CACHE_MODE=PUSH` |
| 2 | **상황 B** | MSA - 캐시 없음 | `CACHE_MODE=NONE` |
| 3 | **상황 C** | 모놀리식 | QR 서비스 중지, Nginx → 모놀리스 |

### 부하 조건

| 항목 | 값 |
|------|-----|
| 총 VU | 1,000명 |
| Wallet VU | 700명 (병목 유발) |
| QR VU | 300명 (측정 대상) |
| 테스트 시간 | 5분 |

### 실행 방법

```bash
cd monitoring/load-tests

# 상황 A: MSA + Redis (현재 상태)
k6 run -e BASE_URL=http://[EC2-IP] scenarios/breakpoint/high-load-test.js

# 상황 B: CACHE_MODE=NONE으로 변경 후
k6 run -e BASE_URL=http://[EC2-IP] scenarios/breakpoint/high-load-test.js

# 상황 C: QR 서비스 중지 후
k6 run -e BASE_URL=http://[EC2-IP] scenarios/breakpoint/high-load-test.js
```

### 예상 결과

| 상황 | 예상 QR p95 | 예상 성능 저하 |
|------|------------|---------------|
| MSA + Redis | 100ms | 2배 |
| MSA (캐시 없음) | 500ms | 10배 |
| 모놀리식 | 2,000ms+ | 50배+ |

### 체크리스트

- [ ] 상황 A 테스트 (MSA + Redis)
- [ ] 상황 B 테스트 (MSA, 캐시 없음)
- [ ] 상황 C 테스트 (모놀리식)
- [ ] 결과 기록 및 비교

---

## 테스트 2: 인덱스 성능 테스트

> 참고: `결제에러시해결방안TEST.md` (섹션 11, 12)

### 목적

`idx_recovery` 인덱스가 결제 복구 쿼리 성능에 미치는 영향 측정

- **쓰기 테스트**: 인덱스가 INSERT 성능에 미치는 오버헤드
- **읽기 테스트**: 인덱스가 `findRecoveryTargets` 쿼리 성능에 미치는 개선 효과

### 대상 인덱스

```sql
-- payment_intent 테이블의 idx_recovery 인덱스
CREATE INDEX idx_recovery ON payment_intent(status, expires_at, created_at);
```

### 테스트 시나리오

| 순서 | 시나리오 | 인덱스 | 작업 |
|------|----------|--------|------|
| 1 | `write_with_index` | O | INSERT (쓰기) |
| 2 | `read_with_index` | O | SELECT (읽기) |
| 3 | `write_without_index` | X | INSERT (쓰기) |
| 4 | `read_without_index` | X | SELECT (읽기) |

### 실행 방법

#### 1단계: QR Service 실행 (loadtest 모드)

```bash
cd services/qr-service
LOADTEST_BACKDOOR_ENABLED=true ./gradlew bootRun
```

#### 2단계: 인덱스 있는 상태에서 테스트

```bash
cd monitoring/load-tests

# 쓰기 테스트 (인덱스 O)
k6 run --env SCENARIO=write_with_index --env QR_SERVICE_URL=http://localhost:8082 scenarios/index-benchmark.js

# 읽기 테스트 (인덱스 O)
k6 run --env SCENARIO=read_with_index --env QR_SERVICE_URL=http://localhost:8082 scenarios/index-benchmark.js
```

#### 3단계: 인덱스 삭제

```sql
-- MySQL 접속
DROP INDEX idx_recovery ON payment_intent;

-- 삭제 확인
SHOW INDEX FROM payment_intent;
```

#### 4단계: 인덱스 없는 상태에서 테스트

```bash
# 쓰기 테스트 (인덱스 X)
k6 run --env SCENARIO=write_without_index --env QR_SERVICE_URL=http://localhost:8082 scenarios/index-benchmark.js

# 읽기 테스트 (인덱스 X)
k6 run --env SCENARIO=read_without_index --env QR_SERVICE_URL=http://localhost:8082 scenarios/index-benchmark.js
```

#### 5단계: 인덱스 복구

```sql
CREATE INDEX idx_recovery ON payment_intent(status, expires_at, created_at);
```

### 예상 결과

| 작업 | 인덱스 O | 인덱스 X | 비고 |
|------|----------|----------|------|
| 쓰기 (INSERT) | 약간 느림 | 기준선 | 인덱스 유지 비용 5-20% |
| 읽기 (SELECT) | **매우 빠름** | 느림 | Index Scan vs Full Table Scan |

### 판단 기준

```
인덱스 도입 OK:
- 쓰기 성능 저하 < 10%
- 읽기 성능 향상 > 50%
- 100만 건에서도 읽기 < 100ms
```

### 체크리스트

- [ ] 인덱스 있을 때 쓰기 테스트
- [ ] 인덱스 있을 때 읽기 테스트
- [ ] 인덱스 삭제
- [ ] 인덱스 없을 때 쓰기 테스트
- [ ] 인덱스 없을 때 읽기 테스트
- [ ] 인덱스 복구
- [ ] 결과 비교 및 분석

---

## 결과 기록 양식

### 테스트 1: 캐싱 전략 결과

| 상황 | QR p95 | QR p99 | 에러율 | 최대 RPS |
|------|--------|--------|--------|----------|
| MSA + Redis | ms | ms | % | |
| MSA (캐시 없음) | ms | ms | % | |
| 모놀리식 | ms | ms | % | |

### 테스트 2: 인덱스 성능 결과

#### 쓰기 성능

| 구분 | 인덱스 O | 인덱스 X | 차이 |
|------|----------|----------|------|
| 평균 | ms | ms | % |
| P95 | ms | ms | % |
| P99 | ms | ms | % |

#### 읽기 성능

| 구분 | 인덱스 O | 인덱스 X | 차이 |
|------|----------|----------|------|
| 평균 | ms | ms | % |
| P95 | ms | ms | % |
| P99 | ms | ms | % |

---

## 관련 파일

| 파일 | 설명 |
|------|------|
| `monitoring/load-tests/scenarios/breakpoint/high-load-test.js` | 캐싱 전략 부하 테스트 |
| `monitoring/load-tests/scenarios/index-benchmark.js` | 인덱스 성능 테스트 |
| `monitoring/load-tests/scenarios/INDEX_BENCHMARK_GUIDE.md` | 인덱스 테스트 가이드 |
| `services/qr-service/.../loadtest/IndexBenchmarkController.java` | 인덱스 테스트용 API |

---

*작성일: 2026-03-07*
