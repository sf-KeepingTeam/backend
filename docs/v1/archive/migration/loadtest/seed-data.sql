-- =============================================
-- Load Test Seed Data for Keeping (Fixed)
-- =============================================

SET FOREIGN_KEY_CHECKS = 0;

-- 기존 데이터 삭제
DELETE FROM wallet_lot_moves;
DELETE FROM wallet_store_lot;
DELETE FROM wallet_store_balances;
DELETE FROM transaction_items;
DELETE FROM transactions;
DELETE FROM settlement_tasks;
DELETE FROM payment_reservations;
DELETE FROM store_favorites;
DELETE FROM charge_bonus;
DELETE FROM menus;
DELETE FROM categories;
DELETE FROM stores;
DELETE FROM customer_pin_auth;
DELETE FROM wallets;
DELETE FROM group_members;
DELETE FROM `groups`;
DELETE FROM notifications;
DELETE FROM fcm_tokens;
DELETE FROM customers;
DELETE FROM owners;

SET FOREIGN_KEY_CHECKS = 1;

-- Auto increment 초기화
ALTER TABLE owners AUTO_INCREMENT = 1;
ALTER TABLE stores AUTO_INCREMENT = 1;
ALTER TABLE categories AUTO_INCREMENT = 1;
ALTER TABLE menus AUTO_INCREMENT = 1;
ALTER TABLE customers AUTO_INCREMENT = 1;
ALTER TABLE wallets AUTO_INCREMENT = 1;
ALTER TABLE transactions AUTO_INCREMENT = 1;
ALTER TABLE wallet_store_lot AUTO_INCREMENT = 1;
ALTER TABLE wallet_store_balances AUTO_INCREMENT = 1;

-- =============================================
-- 1. Owners (10명)
-- =============================================
INSERT INTO owners (provider_id, provider_type, email, phone_number, birth, name, gender, img_url, created_at, updated_at) VALUES
('owner_provider_1', 'KAKAO', 'owner1@test.com', '01011110001', '1980-01-01', '사장님1', 'MALE', NULL, NOW(), NOW()),
('owner_provider_2', 'KAKAO', 'owner2@test.com', '01011110002', '1981-02-02', '사장님2', 'FEMALE', NULL, NOW(), NOW()),
('owner_provider_3', 'KAKAO', 'owner3@test.com', '01011110003', '1982-03-03', '사장님3', 'MALE', NULL, NOW(), NOW()),
('owner_provider_4', 'KAKAO', 'owner4@test.com', '01011110004', '1983-04-04', '사장님4', 'FEMALE', NULL, NOW(), NOW()),
('owner_provider_5', 'KAKAO', 'owner5@test.com', '01011110005', '1984-05-05', '사장님5', 'MALE', NULL, NOW(), NOW()),
('owner_provider_6', 'KAKAO', 'owner6@test.com', '01011110006', '1985-06-06', '사장님6', 'FEMALE', NULL, NOW(), NOW()),
('owner_provider_7', 'KAKAO', 'owner7@test.com', '01011110007', '1986-07-07', '사장님7', 'MALE', NULL, NOW(), NOW()),
('owner_provider_8', 'KAKAO', 'owner8@test.com', '01011110008', '1987-08-08', '사장님8', 'FEMALE', NULL, NOW(), NOW()),
('owner_provider_9', 'KAKAO', 'owner9@test.com', '01011110009', '1988-09-09', '사장님9', 'MALE', NULL, NOW(), NOW()),
('owner_provider_10', 'KAKAO', 'owner10@test.com', '01011110010', '1989-10-10', '사장님10', 'FEMALE', NULL, NOW(), NOW());

-- =============================================
-- 2. Stores (20개)
-- stores_status, img_url NOT NULL
-- =============================================
INSERT INTO stores (owner_id, tax_id_number, store_name, address, phone_number, category, img_url, description, stores_status, created_at, updated_at) VALUES
(1, '111-11-11111', '맛있는 식당 1', '서울시 강남구 테헤란로 1', '02-1111-0001', 'KOREAN', 'https://test.com/img/store1.jpg', '맛있는 한식당', 'ACTIVE', NOW(), NOW()),
(1, '111-11-11112', '맛있는 식당 2', '서울시 강남구 테헤란로 2', '02-1111-0002', 'KOREAN', 'https://test.com/img/store2.jpg', '맛있는 한식당', 'ACTIVE', NOW(), NOW()),
(2, '222-22-22221', '카페 라떼 1', '서울시 서초구 서초대로 1', '02-2222-0001', 'CAFE', 'https://test.com/img/store3.jpg', '분위기 좋은 카페', 'ACTIVE', NOW(), NOW()),
(2, '222-22-22222', '카페 라떼 2', '서울시 서초구 서초대로 2', '02-2222-0002', 'CAFE', 'https://test.com/img/store4.jpg', '분위기 좋은 카페', 'ACTIVE', NOW(), NOW()),
(3, '333-33-33331', '분식천국 1', '서울시 송파구 올림픽로 1', '02-3333-0001', 'SNACK', 'https://test.com/img/store5.jpg', '추억의 분식', 'ACTIVE', NOW(), NOW()),
(3, '333-33-33332', '분식천국 2', '서울시 송파구 올림픽로 2', '02-3333-0002', 'SNACK', 'https://test.com/img/store6.jpg', '추억의 분식', 'ACTIVE', NOW(), NOW()),
(4, '444-44-44441', '치킨매니아 1', '서울시 마포구 홍대로 1', '02-4444-0001', 'CHICKEN', 'https://test.com/img/store7.jpg', '바삭한 치킨', 'ACTIVE', NOW(), NOW()),
(4, '444-44-44442', '치킨매니아 2', '서울시 마포구 홍대로 2', '02-4444-0002', 'CHICKEN', 'https://test.com/img/store8.jpg', '바삭한 치킨', 'ACTIVE', NOW(), NOW()),
(5, '555-55-55551', '피자헛 1', '서울시 영등포구 여의대로 1', '02-5555-0001', 'PIZZA', 'https://test.com/img/store9.jpg', '쫄깃한 피자', 'ACTIVE', NOW(), NOW()),
(5, '555-55-55552', '피자헛 2', '서울시 영등포구 여의대로 2', '02-5555-0002', 'PIZZA', 'https://test.com/img/store10.jpg', '쫄깃한 피자', 'ACTIVE', NOW(), NOW()),
(6, '666-66-66661', '일식당 사쿠라 1', '서울시 종로구 종로 1', '02-6666-0001', 'JAPANESE', 'https://test.com/img/store11.jpg', '정통 일식', 'ACTIVE', NOW(), NOW()),
(6, '666-66-66662', '일식당 사쿠라 2', '서울시 종로구 종로 2', '02-6666-0002', 'JAPANESE', 'https://test.com/img/store12.jpg', '정통 일식', 'ACTIVE', NOW(), NOW()),
(7, '777-77-77771', '중화반점 1', '서울시 중구 명동 1', '02-7777-0001', 'CHINESE', 'https://test.com/img/store13.jpg', '정통 중식', 'ACTIVE', NOW(), NOW()),
(7, '777-77-77772', '중화반점 2', '서울시 중구 명동 2', '02-7777-0002', 'CHINESE', 'https://test.com/img/store14.jpg', '정통 중식', 'ACTIVE', NOW(), NOW()),
(8, '888-88-88881', '버거킹 1', '서울시 용산구 이태원로 1', '02-8888-0001', 'FASTFOOD', 'https://test.com/img/store15.jpg', '맛있는 버거', 'ACTIVE', NOW(), NOW()),
(8, '888-88-88882', '버거킹 2', '서울시 용산구 이태원로 2', '02-8888-0002', 'FASTFOOD', 'https://test.com/img/store16.jpg', '맛있는 버거', 'ACTIVE', NOW(), NOW()),
(9, '999-99-99991', '샐러드바 1', '서울시 강서구 화곡로 1', '02-9999-0001', 'SALAD', 'https://test.com/img/store17.jpg', '신선한 샐러드', 'ACTIVE', NOW(), NOW()),
(9, '999-99-99992', '샐러드바 2', '서울시 강서구 화곡로 2', '02-9999-0002', 'SALAD', 'https://test.com/img/store18.jpg', '신선한 샐러드', 'ACTIVE', NOW(), NOW()),
(10, '100-10-10001', '베이커리 1', '서울시 관악구 관악로 1', '02-1000-0001', 'BAKERY', 'https://test.com/img/store19.jpg', '갓 구운 빵', 'ACTIVE', NOW(), NOW()),
(10, '100-10-10002', '베이커리 2', '서울시 관악구 관악로 2', '02-1000-0002', 'BAKERY', 'https://test.com/img/store20.jpg', '갓 구운 빵', 'ACTIVE', NOW(), NOW());

-- =============================================
-- 3. Categories (Store당 2개)
-- =============================================
INSERT INTO categories (store_id, parent_id, category_name, display_order, created_at, updated_at) VALUES
(1, NULL, '메인메뉴', 1, NOW(), NOW()), (1, NULL, '사이드', 2, NOW(), NOW()),
(2, NULL, '메인메뉴', 1, NOW(), NOW()), (2, NULL, '사이드', 2, NOW(), NOW()),
(3, NULL, '음료', 1, NOW(), NOW()), (3, NULL, '디저트', 2, NOW(), NOW()),
(4, NULL, '음료', 1, NOW(), NOW()), (4, NULL, '디저트', 2, NOW(), NOW()),
(5, NULL, '분식', 1, NOW(), NOW()), (5, NULL, '식사', 2, NOW(), NOW()),
(6, NULL, '분식', 1, NOW(), NOW()), (6, NULL, '식사', 2, NOW(), NOW()),
(7, NULL, '치킨', 1, NOW(), NOW()), (7, NULL, '사이드', 2, NOW(), NOW()),
(8, NULL, '치킨', 1, NOW(), NOW()), (8, NULL, '사이드', 2, NOW(), NOW()),
(9, NULL, '피자', 1, NOW(), NOW()), (9, NULL, '사이드', 2, NOW(), NOW()),
(10, NULL, '피자', 1, NOW(), NOW()), (10, NULL, '사이드', 2, NOW(), NOW()),
(11, NULL, '일식', 1, NOW(), NOW()), (11, NULL, '사이드', 2, NOW(), NOW()),
(12, NULL, '일식', 1, NOW(), NOW()), (12, NULL, '사이드', 2, NOW(), NOW()),
(13, NULL, '중식', 1, NOW(), NOW()), (13, NULL, '사이드', 2, NOW(), NOW()),
(14, NULL, '중식', 1, NOW(), NOW()), (14, NULL, '사이드', 2, NOW(), NOW()),
(15, NULL, '버거', 1, NOW(), NOW()), (15, NULL, '사이드', 2, NOW(), NOW()),
(16, NULL, '버거', 1, NOW(), NOW()), (16, NULL, '사이드', 2, NOW(), NOW()),
(17, NULL, '샐러드', 1, NOW(), NOW()), (17, NULL, '음료', 2, NOW(), NOW()),
(18, NULL, '샐러드', 1, NOW(), NOW()), (18, NULL, '음료', 2, NOW(), NOW()),
(19, NULL, '빵', 1, NOW(), NOW()), (19, NULL, '음료', 2, NOW(), NOW()),
(20, NULL, '빵', 1, NOW(), NOW()), (20, NULL, '음료', 2, NOW(), NOW());

-- =============================================
-- 4. Menus (Store당 10개 = 200개)
-- =============================================
-- Store 1-20 메뉴 생성
INSERT INTO menus (store_id, category_id, menu_name, price, description, sold_out, active, display_order, image_url, created_at, updated_at)
SELECT
    s.store_id,
    (SELECT category_id FROM categories WHERE store_id = s.store_id AND display_order = 1 LIMIT 1),
    CONCAT('메뉴', s.store_id, '-', n.num),
    5000 + (n.num * 1000),
    '맛있는 메뉴입니다',
    false,
    true,
    n.num,
    CONCAT('https://test.com/img/menu', s.store_id, '-', n.num, '.jpg'),
    NOW(),
    NOW()
FROM stores s
CROSS JOIN (SELECT 1 as num UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5) n;

INSERT INTO menus (store_id, category_id, menu_name, price, description, sold_out, active, display_order, image_url, created_at, updated_at)
SELECT
    s.store_id,
    (SELECT category_id FROM categories WHERE store_id = s.store_id AND display_order = 2 LIMIT 1),
    CONCAT('사이드', s.store_id, '-', n.num),
    1000 + (n.num * 500),
    '맛있는 사이드입니다',
    false,
    true,
    n.num + 5,
    CONCAT('https://test.com/img/side', s.store_id, '-', n.num, '.jpg'),
    NOW(),
    NOW()
FROM stores s
CROSS JOIN (SELECT 1 as num UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5) n;

-- =============================================
-- 5. Customers (100명)
-- birth, gender, img_url NOT NULL
-- =============================================
INSERT INTO customers (provider_id, provider_type, email, phone_number, birth, name, gender, img_url, created_at, updated_at)
SELECT
    CONCAT('customer_provider_', n),
    'KAKAO',
    CONCAT('customer', n, '@test.com'),
    CONCAT('0102222', LPAD(n, 4, '0')),
    DATE_SUB('2000-01-01', INTERVAL n DAY),
    CONCAT('고객', n),
    IF(n % 2 = 0, 'MALE', 'FEMALE'),
    CONCAT('https://test.com/img/customer', n, '.jpg'),
    NOW(),
    NOW()
FROM (
    SELECT a.N + b.N * 10 + 1 as n
    FROM (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,
         (SELECT 0 AS N UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b
) numbers
WHERE n <= 100;

-- =============================================
-- 6. CustomerPinAuth (PIN: 123456)
-- =============================================
INSERT INTO customer_pin_auth (customer_id, version, pin_hash, failed_count, locked_until, set_at, updated_at, last_verify_at)
SELECT
    customer_id,
    0,
    '$2a$10$EixZaYVK1fsbw1ZfbX3OXePaWxn96p36WQoeG6Lruj3vjPGga31lW',
    0,
    NULL,
    NOW(),
    NOW(),
    NULL
FROM customers;

-- =============================================
-- 7. Wallets (INDIVIDUAL 타입)
-- =============================================
INSERT INTO wallets (customer_id, group_id, wallet_type, created_at, updated_at)
SELECT customer_id, NULL, 'INDIVIDUAL', NOW(), NOW()
FROM customers;

-- =============================================
-- 8. Transactions (CHARGE) - 각 Customer가 각 Store에 10만원씩 충전
-- =============================================
INSERT INTO transactions (wallet_id, related_wallet_id, customer_id, store_id, ref_tx_id, transaction_type, amount, transaction_unique_no, created_at)
SELECT
    w.wallet_id,
    NULL,
    c.customer_id,
    s.store_id,
    NULL,
    'CHARGE',
    100000,
    CONCAT('TXN-', c.customer_id, '-', s.store_id, '-', UNIX_TIMESTAMP()),
    NOW()
FROM customers c
JOIN wallets w ON w.customer_id = c.customer_id
CROSS JOIN stores s;

-- =============================================
-- 9. WalletStoreLot
-- =============================================
INSERT INTO wallet_store_lot (wallet_id, store_id, contributor_wallet_id, origin_charge_tx_id, cancel_tx_id, amount_total, amount_remaining, acquired_at, expired_at, source_type, lot_status, canceled_at)
SELECT
    t.wallet_id,
    t.store_id,
    NULL,
    t.transaction_id,
    NULL,
    t.amount,
    t.amount,
    NOW(),
    DATE_ADD(NOW(), INTERVAL 1 YEAR),
    'CHARGE',
    'ACTIVE',
    NULL
FROM transactions t
WHERE t.transaction_type = 'CHARGE';

-- =============================================
-- 10. WalletStoreBalance
-- =============================================
INSERT INTO wallet_store_balances (wallet_id, store_id, balance, updated_at)
SELECT
    wallet_id,
    store_id,
    SUM(amount_remaining),
    NOW()
FROM wallet_store_lot
WHERE lot_status = 'ACTIVE'
GROUP BY wallet_id, store_id;

-- =============================================
-- 검증
-- =============================================
SELECT 'Owners' as entity, COUNT(*) as cnt FROM owners
UNION ALL SELECT 'Stores', COUNT(*) FROM stores
UNION ALL SELECT 'Categories', COUNT(*) FROM categories
UNION ALL SELECT 'Menus', COUNT(*) FROM menus
UNION ALL SELECT 'Customers', COUNT(*) FROM customers
UNION ALL SELECT 'CustomerPinAuth', COUNT(*) FROM customer_pin_auth
UNION ALL SELECT 'Wallets', COUNT(*) FROM wallets
UNION ALL SELECT 'Transactions', COUNT(*) FROM transactions
UNION ALL SELECT 'WalletStoreLot', COUNT(*) FROM wallet_store_lot
UNION ALL SELECT 'WalletStoreBalance', COUNT(*) FROM wallet_store_balances;
