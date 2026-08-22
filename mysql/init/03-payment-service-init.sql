-- ================================================
-- Payment Service 초기화 (QR Service용)
-- ================================================

USE payment_service;

-- 테이블은 JPA가 자동 생성 (ddl-auto: update)
-- 초기 데이터 불필요 (런타임에 생성됨)

-- QR Service 테이블 구조 참고:
-- - payment_intent: 결제 의도 정보
-- - payment_intent_item: 결제 품목
-- - idempotency_keys: 멱등성 키
