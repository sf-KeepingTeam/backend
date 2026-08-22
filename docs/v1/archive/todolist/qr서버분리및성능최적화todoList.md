# QR 서버 분리 및 성능 최적화 - 부하 테스트 계획

---

## 기술 선택 요약

| 항목 | 내용 |
|------|------|
| **문제** | 모놀리식 구조에서 Wallet 부하 시 QR 서비스 응답 지연 (25ms → 8,000ms) |
| **해결 후보** | Scale-up, Scale-out, MSA, 캐싱(Cache-Aside, Write-Through, PUSH) |
| **선택** | MSA (서버 분리) + PUSH 모드 캐싱 |
| **이유** | 장애 격리, 독립 배포/확장, DB 의존성 제거로 안정적 응답 시간 확보 |

### 해결 후보 비교

| 솔루션 | 문제 인식 | 장점 | 단점 | 적합성 |
|--------|----------|------|------|--------|
| **Scale-up** | "서버 성능이 부족하다" | 구현 간단, 즉각적 성능 향상 | 비용 증가, 한계 존재, 장애 격리 불가 | ❌ |
| **Scale-out** | "서버 1대로는 처리량이 부족하다" | 수평 확장 가능 | 리소스 공유 문제 미해결, 세션 관리 필요 | △ |
| **MSA** | "Wallet과 QR이 같은 리소스를 쓰는 게 문제다" | 장애 격리, 독립 확장, 기술 스택 분리 | 운영 복잡도 증가, 네트워크 통신 오버헤드 | ✅ |
| **Cache-Aside** | "매번 DB 조회하는 게 병목이다" | 구현 간단, 필요 시 캐싱 | Cache Miss 시 지연, 일관성 문제 | △ |
| **Write-Through** | "DB 조회가 병목 + 캐시 일관성도 중요하다" | 데이터 일관성 보장 | 쓰기 지연, 복잡한 구현 | △ |
| **PUSH 모드** | "Cache Miss 자체가 문제다 (미리 넣어두자)" | Cache Hit 100%, 일관성 보장 | Webhook 인프라 필요, 초기 구현 복잡 | ✅ |

> 💡 **선택 근거**: QR 결제는 5초 만료 정책으로 응답 속도가 핵심. MSA로 물리적 장애 격리 + PUSH 캐싱으로 100% Cache Hit 달성하여 모놀리스 장애에도 안정적 서비스 제공

---

## 목차

- [기술 선택 요약](#기술-선택-요약)
- [목표](#목표)
- [테스트 조건](#테스트-조건)
  - [k6 부하 옵션 설명](#k6-부하-옵션-설명)
  - [k6 Executor 종류](#k6-executor-종류-부하-패턴)
- [TODO List](#todo-list)
  - [Phase 1: 테스트 준비](#phase-1-테스트-준비)
  - [Phase 2: 상황 A - MSA + Redis (현재 상태)](#phase-2-상황-a-테스트---서버-분리--redis-캐시-현재-상태)
  - [Phase 3: 상황 B - MSA (캐시 없음)](#phase-3-상황-b-테스트---서버-분리-캐시-없음)
  - [Phase 4: 상황 C - 모놀리식](#phase-4-상황-c-테스트---단일-서버-모놀리식)
  - [Phase 5: 환경 복구](#phase-5-환경-복구)
  - [Phase 6: 결과 분석 및 문서화](#phase-6-결과-분석-및-문서화)
- [테스트 스크립트 예시](#테스트-스크립트-예시)
- [예상 결과](#예상-결과)
- [주의사항](#주의사항)
- [명령어 정리](#명령어-정리)
- [참고 파일](#참고-파일)

---

## 목표

세 가지 상황에 대해 **동일한 고부하 조건**으로 EC2에서 테스트하여 성능 비교

| 순서 | 상황 | 설명 | 현재 상태 |
|------|------|------|----------|
| 1 | 상황 A | 서버 분리 (MSA) + Redis 캐시 | **현재 코드 상태** (먼저 테스트) |
| 2 | 상황 B | 서버 분리 (MSA) - 캐시 없음 | 코드 수정 후 테스트 |
| 3 | 상황 C | 단일 서버 (모놀리식) | 코드 수정 후 테스트 |

> 💡 **테스트 순서**: 현재 코드 상태(MSA+캐시)부터 시작해서, 점진적으로 기능을 제거하며 비교

---

## 테스트 조건

### 부하 설정

| 항목 | 값 |
|------|-----|
| **총 VU (동시 접속자)** | 1,000명 |
| **목표 RPS** | 500 ~ 1,000 RPS |
| **테스트 시간** | 5분 |
| **부하 패턴** | Ramping (점진적 증가) |

### k6 부하 옵션 설명

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     k6 핵심 개념                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  [VU (Virtual User) = 가상 사용자]                                          │
│                                                                             │
│  - 동시에 요청을 보내는 "사용자 수"                                          │
│  - VU 100 = 100명이 동시에 사이트 이용 중                                    │
│  - 각 VU는 독립적으로 요청 → 대기 → 요청 → 대기... 반복                     │
│                                                                             │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                             │
│  [RPS (Requests Per Second) = 초당 요청 수]                                 │
│                                                                             │
│  - 서버가 1초에 받는 요청 수                                                 │
│  - RPS = VU × (1초 / 평균 응답시간)                                         │
│  - 예: VU 100명, 응답시간 100ms → RPS = 100 × 10 = 1000                    │
│                                                                             │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                             │
│  [VU vs RPS 관계]                                                           │
│                                                                             │
│  VU 100명, 응답 빠름 (50ms)  → RPS 2000 (많은 요청 가능)                    │
│  VU 100명, 응답 느림 (500ms) → RPS 200 (요청 적음)                          │
│                                                                             │
│  → 서버가 느려지면 같은 VU로도 RPS가 떨어짐!                                 │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### k6 Executor 종류 (부하 패턴)

| Executor | 설명 | 용도 |
|----------|------|------|
| `constant-vus` | VU 수 고정 | 일정 부하 테스트 |
| `ramping-vus` | VU 점진적 증가/감소 | 부하 증가 시 성능 변화 측정 |
| `constant-arrival-rate` | **RPS 고정** (VU 자동 조절) | 정확한 처리량 테스트 |
| `ramping-arrival-rate` | RPS 점진적 증가 | 한계점 찾기 |

### k6 옵션 상세 설명

```javascript
export let options = {
  scenarios: {
    my_scenario: {
      // Executor 선택
      executor: 'ramping-arrival-rate',  // RPS 기반 부하

      // 시작 RPS
      startRate: 50,        // 초당 50개 요청으로 시작

      // 시간 단위
      timeUnit: '1s',       // startRate/target의 단위 (1초)

      // VU 관련
      preAllocatedVUs: 500, // 미리 준비할 VU 수
      maxVUs: 1000,         // 최대 VU 수 (RPS 유지 위해 자동 증가)

      // 부하 단계
      stages: [
        { duration: '1m', target: 200 },  // 1분간 RPS 200까지 증가
        { duration: '1m', target: 500 },  // 1분간 RPS 500까지 증가
        { duration: '1m', target: 1000 }, // 1분간 RPS 1000까지 증가
      ],
    },
  },

  // 성능 기준 (이 조건 만족해야 테스트 성공)
  thresholds: {
    'http_req_duration': ['p(95)<500'],  // 95%가 500ms 이내
    'http_req_failed': ['rate<0.01'],    // 에러율 1% 이하
  },
};
```

### 시나리오 구성

```
VU 배분:
├── Wallet 부하: 700 VU (병목 유발)
└── QR 모니터링: 300 VU (핵심 비즈니스 측정)

RPS 배분:
├── Wallet: 최대 700 RPS
└── QR: 300 RPS (고정)
```

### 부하 단계

```
시간        Wallet VU    QR VU     총 VU
0:00-1:00   100 → 300    100       200 → 400
1:00-2:00   300 → 500    200       500 → 700
2:00-3:00   500 → 700    300       800 → 1000
3:00-4:00   700 (유지)   300       1000 (최대 부하)
4:00-5:00   700 → 0      300 → 0   쿨다운
```

---

## TODO List

### Phase 1: 테스트 준비

- [ ] **1.1 테스트 스크립트 수정**
  - 파일: `monitoring/load-tests/scenarios/breakpoint/mixed-load-test.js`
  - 수정 내용:
    - `maxVUs: 300` → `maxVUs: 1000`
    - `preAllocatedVUs: 100` → `preAllocatedVUs: 500`
    - stages에 1000 VU까지 올리는 단계 추가

- [ ] **1.2 고부하 테스트 스크립트 생성**
  - 파일: `monitoring/load-tests/scenarios/breakpoint/high-load-test.js`
  - 내용: 1000 VU, 1000 RPS 전용 스크립트

- [ ] **1.3 EC2 인스턴스 준비**
  - EC2-A (QR 서버): t3.small → t3.medium 업그레이드 검토
  - EC2-B (모놀리스): t3.medium 유지 또는 t3.large 업그레이드 검토
  - 이유: 1000 VU 처리를 위한 리소스 확보

---

### Phase 2: 상황 A 테스트 - 서버 분리 + Redis 캐시 (현재 상태)

> 💡 **현재 코드 상태 그대로 테스트** - 배포 변경 없이 바로 시작

- [ ] **2.1 환경 확인 (현재 상태)**
  - EC2-A: QR 서비스 + Redis 캐시 (이미 배포됨)
  - EC2-B: 모놀리스 (이미 배포됨)
  - `CACHE_MODE=PUSH` 확인

- [ ] **2.2 테스트 실행**
  ```powershell
  # 창 1: Wallet 부하 (EC2-B로)
  k6 run -e BASE_URL=http://[EC2-B-IP] wallet-high-load.js

  # 창 2: QR 부하 (EC2-A로)
  k6 run -e BASE_URL=http://[EC2-A-IP] qr-high-load.js
  ```

- [ ] **2.3 결과 기록**
  - QR p95, p99 응답시간
  - Wallet p95, p99 응답시간
  - 에러율
  - 최대 RPS

---

### Phase 3: 상황 B 테스트 - 서버 분리 (캐시 없음)

> 💡 **Redis 캐시만 비활성화** - QR 서비스 코드 변경 후 재배포

- [ ] **3.1 코드 변경 및 재배포**
  - EC2-A의 QR 서비스: `CACHE_MODE=NONE`으로 변경
  ```bash
  # docker-compose.qr.yml
  environment:
    - CACHE_MODE=NONE

  # 재배포
  docker-compose -f docker-compose.qr.yml down
  docker-compose -f docker-compose.qr.yml up -d
  ```

- [ ] **3.2 환경 확인**
  - EC2-A: QR 서비스 (캐시 없음) - 모놀리스 직접 호출
  - EC2-B: 모놀리스

- [ ] **3.3 테스트 실행**
  ```powershell
  # 창 1: Wallet 부하 (EC2-B로)
  k6 run -e BASE_URL=http://[EC2-B-IP] wallet-high-load.js

  # 창 2: QR 부하 (EC2-A로)
  k6 run -e BASE_URL=http://[EC2-A-IP] qr-high-load.js
  ```

- [ ] **3.4 결과 기록**

---

### Phase 4: 상황 C 테스트 - 단일 서버 (모놀리식)

> 💡 **QR 서비스 중지, 모놀리스만 사용** - Nginx 라우팅 변경

- [ ] **4.1 QR 서비스 중지**
  ```bash
  # EC2-A에서
  docker-compose -f docker-compose.qr.yml down
  ```

- [ ] **4.2 Nginx 설정 변경**
  - 모든 요청 → 모놀리스로 라우팅
  ```nginx
  # EC2-B의 nginx.conf
  location /cpqr {
      proxy_pass http://monolith:8080;
  }
  ```

- [ ] **4.3 환경 확인**
  - EC2-B만 사용 (모놀리스 단독)
  - 캐시 없음

- [ ] **4.4 테스트 실행**
  ```powershell
  k6 run -e BASE_URL=http://[EC2-B-IP] high-load-test.js
  ```

- [ ] **4.5 결과 기록**
  - QR p95, p99 응답시간
  - Wallet p95, p99 응답시간
  - 에러율
  - 최대 RPS

---

### Phase 5: 환경 복구

> 💡 **테스트 완료 후 원래 상태로 복구**

- [ ] **5.1 QR 서비스 복구**
  ```bash
  # EC2-A에서
  export CACHE_MODE=PUSH
  docker-compose -f docker-compose.qr.yml up -d
  ```

- [ ] **5.2 Nginx 설정 복구**
  - QR → EC2-A, 나머지 → EC2-B

- [ ] **5.3 정상 동작 확인**

---

### Phase 6: 결과 분석 및 문서화

- [ ] **6.1 결과 비교표 작성**

  | 순서 | 상황 | QR p95 | QR p99 | 에러율 | 최대 RPS |
  |------|------|--------|--------|--------|----------|
  | 1 | MSA + Redis (현재) | ?ms | ?ms | ?% | ? |
  | 2 | MSA (캐시 없음) | ?ms | ?ms | ?% | ? |
  | 3 | 모놀리식 | ?ms | ?ms | ?% | ? |

- [ ] **6.2 성능 비교 분석**
  - MSA + Redis vs MSA: 캐시 효과 측정
  - MSA vs 모놀리식: 장애 격리 효과 측정

- [ ] **6.3 그래프 생성**
  - 응답시간 비교 그래프
  - 성능 저하율 비교 그래프

- [ ] **6.4 최종 리포트 작성**
  - 파일: `monitoring/load-tests/results/high-load-comparison.md`

---

## 테스트 스크립트 예시

### high-load-test.js (신규 생성 필요)

```javascript
// 참고: monitoring/load-tests/scenarios/breakpoint/mixed-load-test.js

export let options = {
  scenarios: {
    // Wallet 부하 (700 VU, 최대 700 RPS)
    wallet_heavy_load: {
      executor: 'ramping-arrival-rate',
      startRate: 50,
      timeUnit: '1s',
      preAllocatedVUs: 500,
      maxVUs: 1000,
      stages: [
        { duration: '1m', target: 200 },   // 워밍업
        { duration: '1m', target: 400 },   // 중간 부하
        { duration: '1m', target: 700 },   // 높은 부하
        { duration: '1m', target: 700 },   // 최대 부하 유지
        { duration: '1m', target: 0 },     // 쿨다운
      ],
      exec: 'walletHeavyLoad',
    },
    // QR 모니터링 (300 VU, 300 RPS)
    qr_monitor: {
      executor: 'constant-arrival-rate',
      rate: 300,  // 초당 300 요청
      timeUnit: '1s',
      duration: '5m',
      preAllocatedVUs: 300,
      maxVUs: 500,
      exec: 'qrMonitor',
    },
  },
  thresholds: {
    'qr_api_duration': ['p(95)<500', 'p(99)<1000'],
    'wallet_api_duration': ['p(95)<2000'],
    'qr_api_errors': ['count<500'],
  },
};
```

---

## 예상 결과

| 순서 | 상황 | 예상 QR p95 | 예상 성능 저하 | 이유 |
|------|------|------------|---------------|------|
| 1 | MSA + Redis (현재) | 100ms | 2배 | 캐시 히트로 DB 접근 최소화 |
| 2 | MSA (캐시 없음) | 500ms | 10배 | 모놀리스 호출 필요, 하지만 격리됨 |
| 3 | 모놀리식 | 2,000ms+ | 50배+ | Wallet 부하가 QR에 직접 영향 |

---

## 주의사항

1. **EC2 리소스 확인**
   - 1000 VU 테스트 전 EC2 CPU/메모리 여유 확인
   - 필요 시 인스턴스 타입 업그레이드

2. **비용**
   - t3.large 사용 시 추가 비용 발생
   - 테스트 후 인스턴스 중지

3. **테스트 순서** ⚠️ 중요
   - **상황 A (현재 상태) → 상황 B (캐시 제거) → 상황 C (모놀리식)** 순서로 진행
   - 현재 코드 상태부터 시작하여 점진적으로 기능 제거
   - 각 테스트 사이 5분 쿨다운
   - 테스트 완료 후 반드시 환경 복구 (Phase 5)

4. **결과 저장**
   - 각 테스트 결과를 JSON으로 저장
   ```bash
   k6 run --out json=results/situation-a-msa-redis.json high-load-test.js
   k6 run --out json=results/situation-b-msa-only.json high-load-test.js
   k6 run --out json=results/situation-c-monolith.json high-load-test.js
   ```

---

## 명령어 정리

### 환경 전환 (테스트 순서대로)

```bash
# ─────────────────────────────────────────────────────────────
# 상황 A: MSA + Redis (현재 상태) - 변경 없이 바로 테스트
# ─────────────────────────────────────────────────────────────
# 환경 확인만
echo $CACHE_MODE  # PUSH여야 함

# ─────────────────────────────────────────────────────────────
# 상황 B: MSA (캐시 없음) - Redis 비활성화
# ─────────────────────────────────────────────────────────────
# EC2-A에서 실행
docker-compose -f docker-compose.qr.yml down
export CACHE_MODE=NONE
docker-compose -f docker-compose.qr.yml up -d

# ─────────────────────────────────────────────────────────────
# 상황 C: 모놀리식 - QR 서비스 중지
# ─────────────────────────────────────────────────────────────
# EC2-A에서 QR 서비스 중지
docker-compose -f docker-compose.qr.yml down

# EC2-B nginx.conf 수정 (QR 요청도 모놀리스로)
location /cpqr {
    proxy_pass http://monolith:8080;
}

# Nginx 재시작
docker exec nginx nginx -s reload

# ─────────────────────────────────────────────────────────────
# 테스트 완료 후: 환경 복구
# ─────────────────────────────────────────────────────────────
# EC2-A에서 QR 서비스 복구
export CACHE_MODE=PUSH
docker-compose -f docker-compose.qr.yml up -d

# EC2-B nginx.conf 복구 (QR → EC2-A로)
location /cpqr {
    proxy_pass http://qr-service:8081;
}
docker exec nginx nginx -s reload
```

### 테스트 실행

```powershell
# 고부하 테스트 (1000 VU)
cd monitoring/load-tests
k6 run -e BASE_URL=http://[EC2-IP] scenarios/breakpoint/high-load-test.js

# 결과 저장 (상황별로)
k6 run --out json=results/situation-a-msa-redis.json high-load-test.js
k6 run --out json=results/situation-b-msa-only.json high-load-test.js
k6 run --out json=results/situation-c-monolith.json high-load-test.js
```

---

## 참고 파일

### 부하 테스트 스크립트

| 파일 | 설명 |
|------|------|
| `monitoring/load-tests/scenarios/breakpoint/mixed-load-test.js` | 기존 복합 부하 테스트 |
| `monitoring/load-tests/scenarios/breakpoint/qr-breakpoint.js` | QR 한계점 테스트 |
| `monitoring/load-tests/config/common.js` | 공통 설정 |
| `monitoring/load-tests/results/breakpoint-analysis-report.md` | 기존 테스트 결과 |

### 테스트 DB 초기화

| 파일 | 설명 |
|------|------|
| `monitoring/load-tests/data/init-test-data.sql` | 테스트 DB 스키마 + 데이터 초기화 스크립트 |
| `monitoring/load-tests/data/test-data-config.js` | k6에서 사용하는 테스트 데이터 ID 범위 및 헬퍼 함수 |

### 테스트 데이터 규모 (1000 VU 대응)

| 엔티티 | 개수 | ID 범위 |
|--------|------|---------|
| Customers | 1,000 | 10001 ~ 11000 |
| Owners | 100 | 20001 ~ 20100 |
| Stores | 200 | 30001 ~ 30200 |
| Wallets | 1,000 | 40001 ~ 41000 |
| Categories | 400 | 50001 ~ 50400 |
| Menus | 1,600 | 60001 ~ 61600 |
| WalletStoreBalances | 200,000 | 지갑 × 매장 조합 (잔액: 100만원) |

> 💡 **DB 초기화 방법**: `mysql -u root -p < monitoring/load-tests/data/init-test-data.sql`

---

*마지막 업데이트: 2026-02-27*
