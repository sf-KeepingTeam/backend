-- ============================================================
--  멱등키 조회 풀스캔 제거
--  대상 DB: monolith (ssafy_fintech_db)
-- ============================================================
--
--  왜 필요한가 (result.md §11-4-3)
--
--    IdempotencyKeyRepository.findByKeyUuid(UUID) 가
--    인덱스를 못 타고 테이블 전체를 훑고 있었다.
--
--      execs 670회 · 46,232 행/회 · 총 30,975,546행 · 49.4초 (digest 3.7%)
--
--    기존 인덱스로는 이 조회를 못 받는다.
--      uk_idem_scope (actor_type, actor_id, path, key_uuid)  ← key_uuid 가 4번째. 좌측 프리픽스 아님
--      idx_idem_created (created_at)                         ← 무관
--
--  ⚠️ 이건 "인덱스 부재"인데 슬로우 쿼리로도, IO 대기로도 안 잡혔다.
--     버퍼풀 적중률이 99.99992% 라 디스크를 안 치고 메모리에서 스캔하며 CPU 만 태웠기 때문이다.
--     2 vCPU 에서는 그게 곧 병목이다.
--
--  실행 방법 (monolith EC2)
--    set -a; . ./.env; set +a
--    docker exec -i keeping-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" ssafy_fintech_db \
--      < V2026_08_25__idempotency_key_uuid_index.sql
-- ============================================================

-- 이미 있으면 조용히 넘어가도록 프로시저로 감싼다 (MySQL 은 CREATE INDEX IF NOT EXISTS 미지원)
DROP PROCEDURE IF EXISTS add_idem_key_uuid_index;
DELIMITER //
CREATE PROCEDURE add_idem_key_uuid_index()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME   = 'idempotency_keys'
       AND INDEX_NAME   = 'idx_idem_key_uuid'
  ) THEN
    CREATE INDEX idx_idem_key_uuid ON idempotency_keys (key_uuid);
  END IF;
END //
DELIMITER ;
CALL add_idem_key_uuid_index();
DROP PROCEDURE add_idem_key_uuid_index;

-- 확인
SELECT INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME
  FROM information_schema.STATISTICS
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'idempotency_keys'
 ORDER BY INDEX_NAME, SEQ_IN_INDEX;
