-- Saga Log 테이블 생성
-- Outbox 패턴을 위한 이벤트 저장소

CREATE TABLE IF NOT EXISTS saga_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    aggregate_id VARCHAR(64) NOT NULL COMMENT 'PaymentIntent.publicId',
    event_type VARCHAR(50) NOT NULL COMMENT 'FUNDS_CAPTURE, FUNDS_RESTORE, NOTIFICATION_REQUEST, NOTIFICATION_APPROVED',
    target_service VARCHAR(50) NOT NULL COMMENT 'WALLET, NOTIFICATION',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, PROCESSING, COMPLETED, FAILED',
    payload JSON COMMENT '이벤트 페이로드',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '현재 재시도 횟수',
    max_retries INT NOT NULL DEFAULT 3 COMMENT '최대 재시도 횟수',
    next_retry_at DATETIME COMMENT '다음 재시도 예정 시간',
    error_message TEXT COMMENT '마지막 에러 메시지',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3),
    completed_at DATETIME(3),

    INDEX idx_saga_status_retry (status, next_retry_at),
    INDEX idx_saga_aggregate (aggregate_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
