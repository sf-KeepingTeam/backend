-- =============================================================
-- v2 스키마 조사 SQL  (읽기 전용 — SELECT / SHOW 만 사용)
-- 목적: keeping.sql ↔ 실DB 간 어긋남 확인, 마이그레이션 전 사전 점검
-- 실행 대상: ssafy_fintech_db (monolith MySQL)
-- =============================================================

-- ─────────────────────────────────────────────────────────────
-- 1. transactions.transaction_type ENUM 값 확인
--    기대: CHARGE, USE, TRANSFER_IN, TRANSFER_OUT, CANCEL_CHARGE, CANCEL_USE, REFUND
--    REFUND 누락 여부 확인용
-- ─────────────────────────────────────────────────────────────
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE
  FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME   = 'transactions'
   AND COLUMN_NAME  = 'transaction_type';

-- ─────────────────────────────────────────────────────────────
-- 2. transactions.refund_status 컬럼 존재 여부
--    JPA Entity에는 @Column(name="refund_status") 존재하지만
--    keeping.sql DDL에는 누락 — 실DB 상태 확인
-- ─────────────────────────────────────────────────────────────
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT
  FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME   = 'transactions'
   AND COLUMN_NAME  = 'refund_status';
-- 결과가 0행이면 DDL과 일치(누락). 1행이면 실DB에만 존재.

-- ─────────────────────────────────────────────────────────────
-- 3. transactions.transaction_unique_no 길이 확인
--    keeping.sql: VARCHAR(50)  /  JPA Entity: length=200
-- ─────────────────────────────────────────────────────────────
SELECT TABLE_NAME, COLUMN_NAME, CHARACTER_MAXIMUM_LENGTH
  FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME   = 'transactions'
   AND COLUMN_NAME  = 'transaction_unique_no';

-- ─────────────────────────────────────────────────────────────
-- 4. customers 테이블에 user_key 컬럼 존재 여부
--    keeping.sql DDL에 UNIQUE KEY uq_customers_userKey(user_key) 정의됨
--    그러나 컬럼 정의(CREATE TABLE) 자체가 누락 — DDL 오류
-- ─────────────────────────────────────────────────────────────
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE
  FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME   = 'customers'
   AND COLUMN_NAME  = 'user_key';

-- ─────────────────────────────────────────────────────────────
-- 5. wallet_store_lot: expired_settled_at, expired_amount 컬럼 존재 여부
--    마이그레이션 전 사전 확인 (이미 적용됐는지)
-- ─────────────────────────────────────────────────────────────
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
  FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME   = 'wallet_store_lot'
   AND COLUMN_NAME  IN ('expired_settled_at', 'expired_amount');
-- 0행: 미적용(마이그레이션 필요). 2행: 이미 적용됨.

-- ─────────────────────────────────────────────────────────────
-- 6. wallet_store_lot 인덱스 목록
--    idx_lot_expiry_sweep 존재 여부 확인
-- ─────────────────────────────────────────────────────────────
SELECT INDEX_NAME, COLUMN_NAME, SEQ_IN_INDEX, NON_UNIQUE
  FROM INFORMATION_SCHEMA.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME   = 'wallet_store_lot'
 ORDER BY INDEX_NAME, SEQ_IN_INDEX;

-- ─────────────────────────────────────────────────────────────
-- 7. wallet_store_balances 인덱스 이름 확인
--    keeping.sql: idx_wallet_store_wallet / idx_wallet_store_store
--    JPA Entity:  idx_wsb_wallet / idx_wsb_store
-- ─────────────────────────────────────────────────────────────
SELECT INDEX_NAME, COLUMN_NAME, SEQ_IN_INDEX
  FROM INFORMATION_SCHEMA.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME   = 'wallet_store_balances'
 ORDER BY INDEX_NAME, SEQ_IN_INDEX;

-- ─────────────────────────────────────────────────────────────
-- 8. REFUND 타입 거래 존재 여부 (마이그레이션 전 데이터 점검)
--    ENUM에 REFUND가 아직 없으면 이 쿼리 자체가 오류 → 건너뛸 것
-- ─────────────────────────────────────────────────────────────
-- 아래 쿼리는 REFUND가 이미 ENUM에 포함된 경우에만 실행 가능
-- SELECT COUNT(*) AS refund_tx_count
--   FROM transactions
--  WHERE transaction_type = 'REFUND';

-- ─────────────────────────────────────────────────────────────
-- 9. 만료됐지만 잔량이 남은 Lot (만료 소멸 대상)
--    Wave 1 만료 정산 기능의 대상 데이터 규모 파악
-- ─────────────────────────────────────────────────────────────
SELECT COUNT(*)                       AS expired_unsettled_lot_count,
       COALESCE(SUM(amount_remaining), 0) AS expired_unsettled_total_amount
  FROM wallet_store_lot
 WHERE expired_at < NOW()
   AND amount_remaining > 0
   AND lot_status = 'ACTIVE';

-- 상세 (상위 20건)
SELECT lot_id, wallet_id, store_id,
       amount_total, amount_remaining,
       acquired_at, expired_at, lot_status, source_type
  FROM wallet_store_lot
 WHERE expired_at < NOW()
   AND amount_remaining > 0
   AND lot_status = 'ACTIVE'
 ORDER BY expired_at ASC
 LIMIT 20;

-- ─────────────────────────────────────────────────────────────
-- 10. Balance vs Lot 합계 정합성 점검
--     wallet_store_balances.balance ≠ SUM(lot.amount_remaining) 인 행 탐색
--     (ACTIVE Lot 기준 — CANCELED 제외)
-- ─────────────────────────────────────────────────────────────
SELECT b.wallet_id,
       b.store_id,
       b.balance                             AS balance_value,
       COALESCE(lot_sum.remaining_sum, 0)    AS lot_remaining_sum,
       b.balance - COALESCE(lot_sum.remaining_sum, 0) AS diff
  FROM wallet_store_balances b
  LEFT JOIN (
    SELECT wallet_id, store_id,
           SUM(amount_remaining) AS remaining_sum
      FROM wallet_store_lot
     WHERE lot_status = 'ACTIVE'
     GROUP BY wallet_id, store_id
  ) lot_sum ON b.wallet_id = lot_sum.wallet_id
           AND b.store_id  = lot_sum.store_id
 WHERE b.balance <> COALESCE(lot_sum.remaining_sum, 0)
 ORDER BY ABS(b.balance - COALESCE(lot_sum.remaining_sum, 0)) DESC
 LIMIT 50;

-- ─────────────────────────────────────────────────────────────
-- 11. 만료 Lot이 Balance에 아직 반영된 건 수
--     expired_settled_at IS NULL + expired_at < NOW() + amount_remaining > 0
--     (마이그레이션 후 실행용 — 컬럼 존재 시에만)
-- ─────────────────────────────────────────────────────────────
-- 마이그레이션 적용 후 아래 주석 해제하여 사용:
-- SELECT COUNT(*) AS pending_expiry_settle
--   FROM wallet_store_lot
--  WHERE expired_at < NOW()
--    AND amount_remaining > 0
--    AND lot_status = 'ACTIVE'
--    AND expired_settled_at IS NULL;

-- =============================================================
-- END
-- =============================================================
