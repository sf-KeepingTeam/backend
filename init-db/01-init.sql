-- K6 Load Test Data Initialization Script
-- 테이블 생성 + 테스트 데이터 삽입
-- Target: 100 Customers, 50 Owners, 100 Stores, 100 Wallets, 200 Categories, 800 Menus

-- =============================================
-- 데이터베이스 생성 및 선택
-- =============================================
CREATE DATABASE IF NOT EXISTS keeping
CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci;

USE keeping;

-- =============================================
-- 1. CUSTOMERS 테이블 생성
-- =============================================
CREATE TABLE IF NOT EXISTS customers (
    customer_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider_id VARCHAR(100) NOT NULL,
    provider_type VARCHAR(20) NOT NULL,
    email VARCHAR(250) NOT NULL,
    phone_number VARCHAR(50) NOT NULL,
    birth DATE NOT NULL,
    name VARCHAR(50) NOT NULL,
    gender VARCHAR(10) NOT NULL,
    img_url VARCHAR(200) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME NULL,
    UNIQUE KEY uq_customers_provider (provider_type, provider_id),
    UNIQUE KEY uq_customers_email (email),
    UNIQUE KEY uq_customers_phone (phone_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================
-- 2. OWNERS 테이블 생성
-- =============================================
CREATE TABLE IF NOT EXISTS owners (
    owner_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    provider_id VARCHAR(100) NOT NULL,
    provider_type VARCHAR(20) NOT NULL,
    email VARCHAR(250) NULL,
    phone_number VARCHAR(50) NULL,
    birth DATE NULL,
    name VARCHAR(50) NOT NULL,
    gender VARCHAR(10) NULL,
    img_url VARCHAR(200) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    phone_verified_at DATETIME NULL,
    deleted_at DATETIME NULL,
    UNIQUE KEY uq_owners_provider (provider_type, provider_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================
-- 3. STORES 테이블 생성
-- =============================================
CREATE TABLE IF NOT EXISTS stores (
    store_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    tax_id_number VARCHAR(50) NOT NULL,
    store_name VARCHAR(100) NOT NULL,
    address VARCHAR(255) NOT NULL,
    phone_number VARCHAR(50) NULL,
    category VARCHAR(50) NOT NULL,
    img_url VARCHAR(255) NOT NULL,
    description VARCHAR(250) NULL,
    stores_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME NULL,
    UNIQUE KEY uq_stores_tax_address (tax_id_number, address),
    KEY idx_stores_owner (owner_id),
    CONSTRAINT fk_stores_owner FOREIGN KEY (owner_id) REFERENCES owners(owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================
-- 4. WALLETS 테이블 생성
-- =============================================
CREATE TABLE IF NOT EXISTS wallets (
    wallet_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id BIGINT NULL,
    group_id BIGINT NULL,
    wallet_type VARCHAR(20) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_wallets_customer (customer_id),
    KEY idx_wallets_group (group_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================
-- 5. CATEGORIES 테이블 생성
-- =============================================
CREATE TABLE IF NOT EXISTS categories (
    category_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT NOT NULL,
    parent_id BIGINT NULL,
    category_name VARCHAR(100) NOT NULL,
    display_order INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_categories_store (store_id),
    KEY idx_categories_parent (parent_id),
    KEY idx_cat_store_parent_order (store_id, parent_id, display_order),
    CONSTRAINT fk_categories_store FOREIGN KEY (store_id) REFERENCES stores(store_id),
    CONSTRAINT fk_categories_parent FOREIGN KEY (parent_id) REFERENCES categories(category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================
-- 6. MENUS 테이블 생성
-- =============================================
CREATE TABLE IF NOT EXISTS menus (
    menu_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id BIGINT NOT NULL,
    category_id BIGINT NOT NULL,
    menu_name VARCHAR(150) NOT NULL,
    price INT NOT NULL,
    description VARCHAR(500) NULL,
    sold_out TINYINT(1) NOT NULL DEFAULT 0,
    active TINYINT(1) NOT NULL DEFAULT 1,
    display_order INT NOT NULL DEFAULT 0,
    image_url VARCHAR(500) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_at DATETIME NULL,
    KEY idx_menus_store (store_id),
    KEY idx_menus_category (category_id),
    KEY idx_menus_active (active),
    KEY idx_menus_soldout (sold_out),
    CONSTRAINT fk_menus_store FOREIGN KEY (store_id) REFERENCES stores(store_id),
    CONSTRAINT fk_menus_category FOREIGN KEY (category_id) REFERENCES categories(category_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- =============================================
-- 데이터 삽입 시작
-- =============================================
SET FOREIGN_KEY_CHECKS = 0;

-- =============================================
-- 1. CUSTOMERS 데이터 (100명: 10001 ~ 10100)
-- =============================================
INSERT INTO customers (customer_id, provider_id, provider_type, email, phone_number, birth, name, gender, img_url, created_at, updated_at)
SELECT
    10000 + seq AS customer_id,
    CONCAT('loadtest_customer_', 10000 + seq) AS provider_id,
    'KAKAO' AS provider_type,
    CONCAT('loadtest_customer_', 10000 + seq, '@test.com') AS email,
    CONCAT('010-1', LPAD(seq, 3, '0'), '-', LPAD(seq, 4, '0')) AS phone_number,
    DATE_ADD('1980-01-01', INTERVAL (seq MOD 30) YEAR) AS birth,
    CONCAT('테스트고객', seq) AS name,
    IF(seq MOD 2 = 0, 'MALE', 'FEMALE') AS gender,
    CONCAT('https://example.com/customer/', 10000 + seq, '.png') AS img_url,
    NOW() AS created_at,
    NOW() AS updated_at
FROM (
    SELECT @row := @row + 1 AS seq
    FROM (SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9 UNION SELECT 10) t1,
         (SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9 UNION SELECT 10) t2,
         (SELECT @row := 0) t3
    LIMIT 100
) numbers
ON DUPLICATE KEY UPDATE updated_at = NOW();

-- =============================================
-- 2. OWNERS 데이터 (50명: 20001 ~ 20050)
-- =============================================
INSERT INTO owners (owner_id, provider_id, provider_type, email, phone_number, birth, name, gender, img_url, created_at, updated_at)
SELECT
    20000 + seq AS owner_id,
    CONCAT('loadtest_owner_', 20000 + seq) AS provider_id,
    'KAKAO' AS provider_type,
    CONCAT('loadtest_owner_', 20000 + seq, '@test.com') AS email,
    CONCAT('010-2', LPAD(seq, 3, '0'), '-', LPAD(seq, 4, '0')) AS phone_number,
    DATE_ADD('1970-01-01', INTERVAL (seq MOD 25) YEAR) AS birth,
    CONCAT('테스트점주', seq) AS name,
    IF(seq MOD 2 = 0, 'MALE', 'FEMALE') AS gender,
    CONCAT('https://example.com/owner/', 20000 + seq, '.png') AS img_url,
    NOW() AS created_at,
    NOW() AS updated_at
FROM (
    SELECT @row2 := @row2 + 1 AS seq
    FROM (SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9 UNION SELECT 10) t1,
         (SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5) t2,
         (SELECT @row2 := 0) t3
    LIMIT 50
) numbers
ON DUPLICATE KEY UPDATE updated_at = NOW();

-- =============================================
-- 3. STORES 데이터 (100개: 30001 ~ 30100, Owner당 2개)
-- =============================================
INSERT INTO stores (store_id, owner_id, tax_id_number, store_name, address, phone_number, category, img_url, description, stores_status, created_at, updated_at)
SELECT
    30000 + seq AS store_id,
    20001 + ((seq - 1) DIV 2) AS owner_id,
    CONCAT('100-00-', LPAD(30000 + seq, 5, '0')) AS tax_id_number,
    CONCAT('테스트매장', 30000 + seq) AS store_name,
    CONCAT('서울시 강남구 테스트로 ', seq, '길') AS address,
    CONCAT('02-3', LPAD(seq, 3, '0'), '-0001') AS phone_number,
    ELT((seq MOD 10) + 1, 'KOREAN', 'JAPANESE', 'CHINESE', 'WESTERN', 'CAFE', 'CHICKEN', 'PIZZA', 'DESSERT', 'SNACK', 'OTHER') AS category,
    CONCAT('https://example.com/store/', 30000 + seq, '.png') AS img_url,
    CONCAT('부하테스트용 매장 ', seq) AS description,
    'ACTIVE' AS stores_status,
    NOW() AS created_at,
    NOW() AS updated_at
FROM (
    SELECT @row3 := @row3 + 1 AS seq
    FROM (SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9 UNION SELECT 10) t1,
         (SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9 UNION SELECT 10) t2,
         (SELECT @row3 := 0) t3
    LIMIT 100
) numbers
ON DUPLICATE KEY UPDATE updated_at = NOW();

-- =============================================
-- 4. WALLETS 데이터 (100개: 40001 ~ 40100, Customer당 1개)
-- =============================================
INSERT INTO wallets (wallet_id, customer_id, wallet_type, created_at, updated_at)
SELECT
    40000 + seq AS wallet_id,
    10000 + seq AS customer_id,
    'INDIVIDUAL' AS wallet_type,
    NOW() AS created_at,
    NOW() AS updated_at
FROM (
    SELECT @row4 := @row4 + 1 AS seq
    FROM (SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9 UNION SELECT 10) t1,
         (SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9 UNION SELECT 10) t2,
         (SELECT @row4 := 0) t3
    LIMIT 100
) numbers
ON DUPLICATE KEY UPDATE updated_at = NOW();

-- =============================================
-- 5. CATEGORIES 데이터 (200개: 50001 ~ 50200, Store당 2개)
-- =============================================
INSERT INTO categories (category_id, store_id, parent_id, category_name, display_order, created_at, updated_at)
SELECT
    50000 + seq AS category_id,
    30001 + ((seq - 1) DIV 2) AS store_id,
    NULL AS parent_id,
    IF((seq MOD 2) = 1, '메인메뉴', '사이드메뉴') AS category_name,
    ((seq - 1) MOD 2) + 1 AS display_order,
    NOW() AS created_at,
    NOW() AS updated_at
FROM (
    SELECT @row5 := @row5 + 1 AS seq
    FROM (SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9 UNION SELECT 10) t1,
         (SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9 UNION SELECT 10) t2,
         (SELECT 1 UNION SELECT 2) t3,
         (SELECT @row5 := 0) t4
    LIMIT 200
) numbers
ON DUPLICATE KEY UPDATE updated_at = NOW();

-- =============================================
-- 6. MENUS 데이터 (800개: 60001 ~ 60800, Category당 4개)
-- =============================================
INSERT INTO menus (menu_id, store_id, category_id, menu_name, price, description, sold_out, active, display_order, image_url, created_at, updated_at)
SELECT
    60000 + seq AS menu_id,
    30001 + ((seq - 1) DIV 8) AS store_id,
    50001 + ((seq - 1) DIV 4) AS category_id,
    CONCAT('테스트메뉴_', ((seq - 1) MOD 4) + 1) AS menu_name,
    5000 + (((seq - 1) MOD 4) + 1) * 1000 + ((seq MOD 10) * 500) AS price,
    CONCAT('맛있는 테스트메뉴 ', seq, ' 입니다.') AS description,
    0 AS sold_out,
    1 AS active,
    ((seq - 1) MOD 4) + 1 AS display_order,
    CONCAT('https://example.com/menu/', 60000 + seq, '.png') AS image_url,
    NOW() AS created_at,
    NOW() AS updated_at
FROM (
    SELECT @row6 := @row6 + 1 AS seq
    FROM (SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9 UNION SELECT 10) t1,
         (SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9 UNION SELECT 10) t2,
         (SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4 UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8) t3,
         (SELECT @row6 := 0) t4
    LIMIT 800
) numbers
ON DUPLICATE KEY UPDATE updated_at = NOW();

SET FOREIGN_KEY_CHECKS = 1;

-- =============================================
-- 검증 쿼리
-- =============================================
SELECT '========== 데이터 삽입 완료 ==========' AS message;

SELECT 'Customers' AS entity, COUNT(*) AS count FROM customers WHERE customer_id BETWEEN 10001 AND 10100
UNION ALL
SELECT 'Owners', COUNT(*) FROM owners WHERE owner_id BETWEEN 20001 AND 20050
UNION ALL
SELECT 'Stores', COUNT(*) FROM stores WHERE store_id BETWEEN 30001 AND 30100
UNION ALL
SELECT 'Wallets', COUNT(*) FROM wallets WHERE wallet_id BETWEEN 40001 AND 40100
UNION ALL
SELECT 'Categories', COUNT(*) FROM categories WHERE category_id BETWEEN 50001 AND 50200
UNION ALL
SELECT 'Menus', COUNT(*) FROM menus WHERE menu_id BETWEEN 60001 AND 60800;
