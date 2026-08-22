-- =============================================================
-- V2025_08_21__lot_expiry_columns_rollback.sql
-- Wave 1 롤백: Lot 만료 정산 컬럼 제거 + REFUND ENUM 제거
-- 대상 DB: ssafy_fintech_db (monolith)
--
-- 주의:
--   REFUND ENUM 제거는 해당 값을 사용하는 행이 0건일 때만 안전.
--   행이 존재하면 ALTER MODIFY가 데이터 손실을 유발한다.
--   롤백 전 반드시 아래 쿼리로 확인:
--     SELECT COUNT(*) FROM transactions WHERE transaction_type = 'REFUND';
-- =============================================================

-- ─────────────────────────────────────────────────────────────
-- 1. wallet_store_lot: 인덱스 + 컬럼 제거
-- ─────────────────────────────────────────────────────────────
ALTER TABLE `wallet_store_lot`
  DROP KEY `idx_lot_expiry_sweep`;

ALTER TABLE `wallet_store_lot`
  DROP COLUMN `expired_settled_at`,
  DROP COLUMN `expired_amount`;

-- ─────────────────────────────────────────────────────────────
-- 2. transactions.transaction_type ENUM에서 REFUND 제거 (조건부)
--    REFUND 값을 사용하는 행이 있으면 실패시킴
-- ─────────────────────────────────────────────────────────────
DROP PROCEDURE IF EXISTS `__remove_refund_from_transaction_type`;

DELIMITER $$
CREATE PROCEDURE `__remove_refund_from_transaction_type`()
BEGIN
  DECLARE refund_count INT DEFAULT 0;
  DECLARE current_type TEXT;

  SELECT COLUMN_TYPE INTO current_type
    FROM INFORMATION_SCHEMA.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME   = 'transactions'
     AND COLUMN_NAME  = 'transaction_type';

  -- REFUND가 ENUM에 없으면 이미 롤백된 상태
  IF current_type LIKE '%REFUND%' THEN
    SELECT COUNT(*) INTO refund_count
      FROM `transactions`
     WHERE `transaction_type` = 'REFUND';

    IF refund_count > 0 THEN
      SIGNAL SQLSTATE '45000'
        SET MESSAGE_TEXT = 'ROLLBACK BLOCKED: transactions 테이블에 REFUND 타입 행이 존재합니다. 데이터 정리 후 재시도하세요.';
    END IF;

    ALTER TABLE `transactions`
      MODIFY COLUMN `transaction_type`
        ENUM('CHARGE','USE','TRANSFER_IN','TRANSFER_OUT',
             'CANCEL_CHARGE','CANCEL_USE') NOT NULL;
  END IF;
END$$
DELIMITER ;

CALL `__remove_refund_from_transaction_type`();
DROP PROCEDURE IF EXISTS `__remove_refund_from_transaction_type`;

-- =============================================================
-- END
-- =============================================================
