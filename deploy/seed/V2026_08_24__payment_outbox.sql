-- payment_service DB (qr-service 소유)
-- 아웃박스 패턴: 결제 이벤트를 트랜잭션과 함께 기록하고, 폴러가 Kafka 로 발행한다.
CREATE TABLE IF NOT EXISTS payment_outbox (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  event_id        CHAR(36)     NOT NULL,
  aggregate_type  VARCHAR(50)  NOT NULL,
  aggregate_id    BIGINT       NOT NULL,
  event_type      VARCHAR(50)  NOT NULL,
  partition_key   VARCHAR(64)  NOT NULL,
  payload         TEXT         NOT NULL,
  status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
  retry_count     INT          NOT NULL DEFAULT 0,
  created_at      DATETIME(3)  NOT NULL,
  sent_at         DATETIME(3)  NULL,
  last_error      VARCHAR(500) NULL,
  PRIMARY KEY (id),

  -- 재시도 시 같은 이벤트가 두 행이 되는 것을 막는다
  UNIQUE KEY uk_outbox_event_id (event_id),

  -- 폴러가 WHERE status='PENDING' ORDER BY id LIMIT N 으로 읽는다
  KEY idx_outbox_poll (status, id),

  -- 정리 배치가 WHERE status='SENT' AND sent_at < ? 로 오래된 행을 삭제한다
  KEY idx_outbox_cleanup (status, sent_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
