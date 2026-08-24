-- keeping DB (monolith 소유)
-- 알림 이벤트 멱등성 테이블.
-- notifications 테이블에 event_id UNIQUE 를 붙일 수 없다:
--   이벤트 1건이 알림 2건(점주+고객)을 만들기 때문이다.
-- 별도 테이블이 맞다.
-- idx_processed_at 은 나중에 정리 배치를 붙일 때 사용한다.
CREATE TABLE IF NOT EXISTS processed_event (
  event_id     CHAR(36)    NOT NULL,
  event_type   VARCHAR(50) NOT NULL,
  processed_at DATETIME(3) NOT NULL,
  PRIMARY KEY (event_id),
  KEY idx_processed_at (processed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
