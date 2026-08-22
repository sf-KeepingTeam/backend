# 16_schema: Wave 0/1 스키마 대조 + 마이그레이션

작성일: 2025-08-21
담당: schema-agent

---

## 1. Wave 0 — 정적 스키마 대조 결과

`keeping.sql` (참조 DDL) vs JPA Entity 필드 단위 비교.

### 1-1. transactions 테이블

| # | 항목 | keeping.sql | JPA Entity (Transaction.java) | 상태 |
|---|------|-------------|-------------------------------|------|
| 1 | `transaction_type` ENUM | `CHARGE,USE,TRANSFER_IN,TRANSFER_OUT,CANCEL_CHARGE,CANCEL_USE` (6개) | `TransactionType` enum에 `REFUND` 포함 (7개) | **불일치 — 이번 마이그레이션에서 수정** |
| 2 | `refund_status` 컬럼 | **없음** | `@Column(name="refund_status")` + `RefundStatus` enum (`REFUND_PENDING/REFUND_DONE/REFUND_PERMANENT_FAILED`) 존재 | **불일치 — DDL에 컬럼 정의 누락. 이번 스코프 외** |
| 3 | `transaction_unique_no` 길이 | `VARCHAR(50)` | `@Column(length=200)` | **불일치 — 실DB 확인 필요. 이번 스코프 외** |
| 4 | `customer_id` | `NOT NULL` | `@JoinColumn(nullable=false)` — 일치하지만, `processRefund` 로직에서 customer 미설정 가능성 지적됨 | 로직 주의 |

### 1-2. wallet_store_lot 테이블

| # | 항목 | keeping.sql | JPA Entity (WalletStoreLot.java) | 상태 |
|---|------|-------------|----------------------------------|------|
| 5 | `expired_settled_at` 컬럼 | 없음 | 없음 (v2에서 추가 예정) | **이번 마이그레이션에서 추가** |
| 6 | `expired_amount` 컬럼 | 없음 | 없음 (v2에서 추가 예정) | **이번 마이그레이션에서 추가** |
| 7 | `amount_total` / `amount_remaining` 타입 | `BIGINT UNSIGNED` | `Long` (signed) | 범주적 불일치 — Java Long은 음수 허용. CHECK 제약으로 보완됨 |

### 1-3. wallet_store_balances 테이블

| # | 항목 | keeping.sql | JPA Entity (WalletStoreBalance.java) | 상태 |
|---|------|-------------|---------------------------------------|------|
| 8 | 인덱스 이름 | `idx_wallet_store_wallet`, `idx_wallet_store_store` | `idx_wsb_wallet`, `idx_wsb_store` | **이름 불일치** — Hibernate `ddl-auto=validate`가 이름까지 검증하지 않으므로 기동에는 영향 없음. 실DB 확인 필요 |
| 9 | `balance` 타입 | `BIGINT UNSIGNED ... DEFAULT 0.00` | `Long` (signed) | 범주적 불일치 + DEFAULT 값이 `0.00`인데 정수 컬럼 (MySQL이 `0`으로 해석하나 의도가 불분명) |

### 1-4. customers 테이블 (DDL 자체 오류)

| # | 항목 | 상태 |
|---|------|------|
| 10 | `user_key` 컬럼 | `UNIQUE KEY uq_customers_userKey (user_key)` 정의됐으나 **컬럼 정의가 CREATE TABLE에 없음**. `owners` 테이블에만 존재. DDL을 그대로 실행하면 오류 발생 |

### 1-5. 기타

| # | 항목 | 상태 |
|---|------|------|
| 11 | `BIGINT UNSIGNED` vs `Long` 전반 | `transactions.amount`, `wallet_store_lot.amount_*`, `wallet_store_balances.balance` 모두 해당. Java Long(-2^63~2^63-1)은 UNSIGNED 범위(0~2^64-1)를 완전히 커버하지 못하나, CHECK 제약(`amount > 0` 등)과 비즈니스 로직상 실질 문제 없음 |

---

## 2. Wave 1 — 마이그레이션 산출물

### 2-1. 마이그레이션 스크립트

**파일**: `mysql/migration/V2025_08_21__lot_expiry_columns.sql`

변경 내용:
1. `wallet_store_lot` 테이블에 컬럼 2개 추가
   - `expired_settled_at DATETIME(3) NULL` — 만료분 balance 차감 시각 (멱등 마커)
   - `expired_amount BIGINT UNSIGNED NULL` — 만료 시점 잔량 (소멸액)
2. `wallet_store_lot` 테이블에 인덱스 추가
   - `idx_lot_expiry_sweep (expired_settled_at, expired_at, lot_id)` — 만료 스위프 배치용
3. `transactions.transaction_type` ENUM에 `REFUND` 값 추가
   - `INFORMATION_SCHEMA` 조회 후 조건부 ALTER (멱등)

ALTER 소요 시간: **측정하지 못했다.** 행 수에 따라 ALGORITHM=INSTANT 가능 여부가 달라지며, ENUM 변경은 테이블 리빌드가 필요할 수 있다. 프로덕션 적용 전 스테이징에서 반드시 실측할 것.

### 2-2. 롤백 스크립트

**파일**: `mysql/migration/V2025_08_21__lot_expiry_columns_rollback.sql`

- 인덱스 `idx_lot_expiry_sweep` 삭제
- 컬럼 `expired_settled_at`, `expired_amount` 삭제
- `REFUND` ENUM 값 제거 (단, `transaction_type='REFUND'` 행이 존재하면 SIGNAL로 차단)

### 2-3. keeping.sql 갱신

- `transactions.transaction_type` ENUM에 `REFUND` 추가
- `wallet_store_lot` 테이블에 `expired_settled_at`, `expired_amount` 컬럼 + `idx_lot_expiry_sweep` 인덱스 추가

### 2-4. 조사 SQL

**파일**: `mysql/ops/v2_스키마조사.sql`

포함 쿼리:
- `transaction_type` ENUM 값 확인
- `refund_status` 컬럼 존재 여부
- `transaction_unique_no` 길이 확인
- `customers.user_key` 컬럼 존재 여부
- `expired_settled_at`, `expired_amount` 컬럼 존재 여부 (마이그레이션 적용 확인)
- `wallet_store_lot` 인덱스 목록
- `wallet_store_balances` 인덱스 이름 확인
- 만료됐지만 잔량 남은 Lot 집계 (만료 소멸 대상)
- Balance vs Lot 합계 정합성 점검

---

## 3. 이번 스코프에서 수정하지 않은 불일치 (후속 작업 필요)

| # | 항목 | 사유 |
|---|------|------|
| 2 | `transactions.refund_status` 컬럼 DDL 누락 | Wave 1 범위 외. 실DB 상태를 조사 SQL로 확인 후 별도 마이그레이션 |
| 3 | `transaction_unique_no` VARCHAR(50) vs length=200 | 실DB 확인 후 결정. Entity 수정 또는 DDL 변경 필요 |
| 8 | `wallet_store_balances` 인덱스 이름 불일치 | 기능 영향 없음. DDL 참조용 keeping.sql 수정 또는 Entity 수정으로 통일 |
| 9 | `balance` DEFAULT 0.00 (정수에 소수점) | MySQL이 0으로 해석하나 의도 불분명. 정리 권장 |
| 10 | `customers.user_key` 컬럼 정의 누락 | DDL 자체 오류. 실DB에서 컬럼 존재 여부 확인 후 keeping.sql 수정 |

---

## 4. 하지 않은 것

- `.java` 파일 수정 (금지 사항)
- `LotStatus`에 `EXPIRED` 추가 (금지 사항)
- `TransactionType`에 `EXPIRE` 추가 (금지 사항)
- DB 접속 (금지 사항)
- ALTER 소요 시간 추정 ("측정하지 못했다"로 명시)
