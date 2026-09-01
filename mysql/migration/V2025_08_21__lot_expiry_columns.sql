-- =============================================================
-- V2025_08_21__lot_expiry_columns.sql
-- Wave 1 마이그레이션: Lot 만료 정산 컬럼 + REFUND ENUM 추가
-- 대상 DB: ssafy_fintech_db (monolith)
-- =============================================================

-- ─────────────────────────────────────────────────────────────
-- 1. wallet_store_lot: 만료 정산 컬럼 2개 + 인덱스 추가
-- ─────────────────────────────────────────────────────────────
ALTER TABLE `wallet_store_lot`
  ADD COLUMN `expired_settled_at` DATETIME(3) NULL
      COMMENT '만료분을 balance에서 차감한 시각(멱등 마커)',
  ADD COLUMN `expired_amount` BIGINT UNSIGNED NULL
      COMMENT '만료 시점 잔량(소멸액)',
  ADD KEY `idx_lot_expiry_sweep` (`expired_settled_at`, `expired_at`, `lot_id`);

-- ─────────────────────────────────────────────────────────────
-- 2. transactions.transaction_type ENUM에 REFUND 추가 (멱등)
--    INFORMATION_SCHEMA를 조회하여 REFUND가 이미 포함되어 있으면 건너뜀
-- ─────────────────────────────────────────────────────────────
DROP PROCEDURE IF EXISTS `__add_refund_to_transaction_type`;

DELIMITER $$
CREATE PROCEDURE `__add_refund_to_transaction_type`()
BEGIN
  DECLARE current_type TEXT;

  SELECT COLUMN_TYPE INTO current_type
    FROM INFORMATION_SCHEMA.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME   = 'transactions'
     AND COLUMN_NAME  = 'transaction_type';

  IF current_type NOT LIKE '%REFUND%' THEN
    ALTER TABLE `transactions`
      MODIFY COLUMN `transaction_type`
        ENUM('CHARGE','USE','TRANSFER_IN','TRANSFER_OUT',
             'CANCEL_CHARGE','CANCEL_USE','REFUND') NOT NULL;
  END IF;
END$$
DELIMITER ;

CALL `__add_refund_to_transaction_type`();
DROP PROCEDURE IF EXISTS `__add_refund_to_transaction_type`;

-- =============================================================
-- END
-- =============================================================
