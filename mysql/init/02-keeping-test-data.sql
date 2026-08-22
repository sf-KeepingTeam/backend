-- ================================================
-- Keeping 테스트 데이터 (모놀리스용)
-- QR 결제 플로우 테스트를 위한 초기 데이터
-- ================================================

USE keeping;

-- 기존 테스트 데이터 삭제 (외래키 순서 고려)
SET FOREIGN_KEY_CHECKS = 0;
DELETE FROM customer_pin_auth WHERE customer_id IN (1, 2);
DELETE FROM wallet_store_balances WHERE balance_id = 1;
DELETE FROM wallet_store_lot WHERE lot_id = 1;
DELETE FROM transactions WHERE transaction_id = 1;
DELETE FROM group_members WHERE group_member_id IN (1, 2);
DELETE FROM wallets WHERE wallet_id IN (1, 2, 3);
DELETE FROM `groups` WHERE group_id = 1;
DELETE FROM menus WHERE menu_id IN (1, 2, 3);
DELETE FROM categories WHERE category_id = 1;
DELETE FROM stores WHERE store_id = 1;
DELETE FROM owners WHERE owner_id = 1;
DELETE FROM customers WHERE customer_id IN (1, 2);
SET FOREIGN_KEY_CHECKS = 1;

-- 1. 손님 (Customers) - 2명
INSERT INTO customers (customer_id, provider_id, provider_type, email, phone_number, birth, name, gender, img_url, created_at, updated_at) VALUES
(1, 'kakao_customer_001', 'KAKAO', 'customer1@test.com', '010-1111-1111', '1990-01-01', '테스트손님1', 'MALE', 'https://example.com/img1.png', NOW(), NOW()),
(2, 'kakao_customer_002', 'KAKAO', 'customer2@test.com', '010-2222-2222', '1995-05-15', '테스트손님2', 'FEMALE', 'https://example.com/img2.png', NOW(), NOW());

-- 2. 점주 (Owners) - 1명
INSERT INTO owners (owner_id, provider_id, provider_type, name, created_at, updated_at) VALUES
(1, 'kakao_owner_001', 'KAKAO', '테스트점주', NOW(), NOW());

-- 3. 매장 (Stores) - 1개
INSERT INTO stores (store_id, owner_id, tax_id_number, store_name, address, category, img_url, stores_status, created_at) VALUES
(1, 1, '123-45-67890', '테스트카페', '서울시 강남구 테스트로 123', 'CAFE', 'https://example.com/store.png', 'ACTIVE', NOW());

-- 4. 메뉴 카테고리 (Categories) - 1개
INSERT INTO categories (category_id, store_id, category_name, display_order, created_at, updated_at) VALUES
(1, 1, '음료', 1, NOW(), NOW());

-- 5. 메뉴 (Menus) - 3개
INSERT INTO menus (menu_id, store_id, category_id, menu_name, price, sold_out, active, display_order, image_url, created_at) VALUES
(1, 1, 1, '아메리카노', 4500, 0, 1, 1, 'https://example.com/americano.png', NOW()),
(2, 1, 1, '카페라떼', 5000, 0, 1, 2, 'https://example.com/latte.png', NOW()),
(3, 1, 1, '카푸치노', 5500, 0, 1, 3, 'https://example.com/cappuccino.png', NOW());

-- 6. 그룹 (Groups) - 1개
INSERT INTO `groups` (group_id, group_name, group_code, group_description, created_at, updated_at) VALUES
(1, '테스트그룹', 'TEST_GROUP_001', '테스트용 그룹입니다', NOW(), NOW());

-- 7. 개인 지갑 (Wallets - INDIVIDUAL) - 2개
INSERT INTO wallets (wallet_id, customer_id, wallet_type, created_at, updated_at) VALUES
(1, 1, 'INDIVIDUAL', NOW(), NOW()),
(2, 2, 'INDIVIDUAL', NOW(), NOW());

-- 8. 그룹 지갑 (Wallets - GROUP) - 1개
INSERT INTO wallets (wallet_id, group_id, wallet_type, created_at, updated_at) VALUES
(3, 1, 'GROUP', NOW(), NOW());

-- 9. 그룹 멤버 (GroupMembers) - 2명 (손님1=리더, 손님2=멤버)
INSERT INTO group_members (group_member_id, group_id, customer_id, leader, created_at) VALUES
(1, 1, 1, 1, NOW()),
(2, 1, 2, 0, NOW());

-- 10. 충전 거래 (Transactions - CHARGE) - 그룹 지갑에 50000원 충전
INSERT INTO transactions (transaction_id, wallet_id, customer_id, store_id, transaction_type, amount, created_at) VALUES
(1, 3, 1, 1, 'CHARGE', 50000, NOW());

-- 11. 포인트 LOT (WalletStoreLot) - 그룹 지갑에 50000원
INSERT INTO wallet_store_lot (lot_id, wallet_id, store_id, amount_total, amount_remaining, acquired_at, expired_at, source_type, origin_charge_tx_id, lot_status) VALUES
(1, 3, 1, 50000, 50000, NOW(), DATE_ADD(NOW(), INTERVAL 1 YEAR), 'CHARGE', 1, 'ACTIVE');

-- 12. 매장별 잔액 (WalletStoreBalance) - 그룹 지갑 50000원
INSERT INTO wallet_store_balances (balance_id, wallet_id, store_id, balance, updated_at) VALUES
(1, 3, 1, 50000, NOW());

-- 13. PIN 인증 (CustomerPinAuth) - 손님들 PIN 설정 (123456)
-- PIN: 123456 (BCrypt 해시)
INSERT INTO customer_pin_auth (customer_id, pin_hash, failed_count, set_at, updated_at, version) VALUES
(1, '$2a$10$N9qo8uLOickgx2ZMRZoMye.IjqQBrkHx3PLz9INXdKh4j.06C7wXq', 0, NOW(), NOW(), 0),
(2, '$2a$10$N9qo8uLOickgx2ZMRZoMye.IjqQBrkHx3PLz9INXdKh4j.06C7wXq', 0, NOW(), NOW(), 0);
