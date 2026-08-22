-- =============================================================================
-- 부하측정용 시드 데이터
-- =============================================================================
-- 목적: 결제 플로우(QR 생성 → scan → initiate → approve)가 성립하는 상태를 만든다.
--
-- ★ 기존 monitoring/load-tests/data/init-test-data.sql 은 쓸 수 없다:
--    - customer_pin_auth 를 안 만든다  → approve()의 PIN 검증이 전원 실패
--    - wallet_store_lot 을 안 만든다   → capture()의 lotLeft != 0 경로를 매 결제마다 탐
--                                        (장부가 처음부터 깨진 상태로 측정하게 된다)
--    - DB 이름이 keeping (실제는 ssafy_fintech_db)
--
-- ★ 불변식을 성립시킨다:
--      SUM(wallet_store_lot.amount_remaining WHERE 미만료 AND ACTIVE)
--        == wallet_store_balances.balance
--    transactions(CHARGE) → wallet_store_lot(origin_charge_tx_id 참조) → wallet_store_balances
--    순서로 짝을 맞춰 넣는다.
--
-- ★ PIN 은 Argon2id 다 (BCrypt 아님).
--    monolith/global/config/PasswordConfig.java:
--      new Argon2PasswordEncoder(16, 32, 1, 1<<13, 3)   // saltLen, hashLen, parallelism, 8192KB, 3회
--    아래 해시는 평문 "123456" 을 같은 파라미터로 만든 값이다.
--
-- 실행:
--   docker exec -i keeping-mysql mysql -uroot -p<PW> ssafy_fintech_db < seed-loadtest.sql
--
-- 재실행 가능하다 (앞에서 전부 TRUNCATE 한다).
-- =============================================================================

-- ── 규모 (k6 common.js 의 TEST_DATA 와 반드시 일치시킬 것) ───────────────────
SET @CUSTOMERS       = 1000;   -- = 지갑 수. 최대 VU 보다 커야 한다
                               --   (같은 지갑에 비관락이 몰리면 측정이 오염된다)
SET @STORES          = 100;
SET @MENUS_PER_STORE = 5;
SET @BALANCE         = 100000000;  -- 매장당 1억. 러닝 중에 소진되지 않게 넉넉히

SET @PIN_HASH = '$argon2id$v=19$m=8192,t=3,p=1$S2VlcGluZ0xvYWRUZXN0IQ$KkraKi3ns5dMOAqWE8yRVTghUtaww6CZuT0iEALZwl0';
SET @NOW = NOW(6);

SET FOREIGN_KEY_CHECKS = 0;
SET UNIQUE_CHECKS = 0;

-- ── 초기화 ────────────────────────────────────────────────────────────────
TRUNCATE TABLE wallet_lot_moves;
TRUNCATE TABLE wallet_store_lot;
TRUNCATE TABLE wallet_store_balances;
TRUNCATE TABLE transaction_items;
TRUNCATE TABLE transactions;
TRUNCATE TABLE customer_pin_auth;
TRUNCATE TABLE idempotency_keys;
TRUNCATE TABLE notifications;
TRUNCATE TABLE fcm_tokens;
TRUNCATE TABLE payment_reservations;
TRUNCATE TABLE settlement_tasks;
TRUNCATE TABLE charge_bonus;
TRUNCATE TABLE store_favorites;
TRUNCATE TABLE group_add_requests;
TRUNCATE TABLE group_members;
TRUNCATE TABLE menus;
TRUNCATE TABLE categories;
TRUNCATE TABLE wallets;
TRUNCATE TABLE `groups`;
TRUNCATE TABLE stores;
TRUNCATE TABLE owners;
TRUNCATE TABLE customers;

-- ── 숫자 테이블 (0 ~ 9999) ────────────────────────────────────────────────
DROP TABLE IF EXISTS _seed_seq;
CREATE TABLE _seed_seq (n INT PRIMARY KEY);
INSERT INTO _seed_seq (n)
SELECT a.i + b.i*10 + c.i*100 + d.i*1000
FROM (SELECT 0 i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) a
CROSS JOIN (SELECT 0 i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) b
CROSS JOIN (SELECT 0 i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) c
CROSS JOIN (SELECT 0 i UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4
      UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9) d;

-- ── 1. 고객 ───────────────────────────────────────────────────────────────
INSERT INTO customers
  (customer_id, birth, created_at, email, gender, img_url, name, phone_number,
   provider_id, provider_type, updated_at)
SELECT n, '1990-01-01', @NOW, CONCAT('cust', n, '@loadtest.local'),
       IF(n % 2 = 0, 'MALE', 'FEMALE'), '', CONCAT('고객', n),
       CONCAT('010', LPAD(n, 8, '0')), CONCAT('kakao-cust-', n), 'KAKAO', @NOW
FROM _seed_seq WHERE n BETWEEN 1 AND @CUSTOMERS;

-- ── 2. PIN (평문 123456) ──────────────────────────────────────────────────
--    이게 없으면 approve() 의 /internal/customers/{id}/pin-verify 가 전원 실패한다
INSERT INTO customer_pin_auth
  (customer_id, failed_count, pin_hash, set_at, updated_at, version)
SELECT n, 0, @PIN_HASH, @NOW, @NOW, 0
FROM _seed_seq WHERE n BETWEEN 1 AND @CUSTOMERS;

-- ── 3. 점주 ───────────────────────────────────────────────────────────────
INSERT INTO owners
  (owner_id, created_at, name, provider_id, provider_type, updated_at)
SELECT n, @NOW, CONCAT('사장', n), CONCAT('kakao-owner-', n), 'KAKAO', @NOW
FROM _seed_seq WHERE n BETWEEN 1 AND @STORES;

-- ── 4. 매장 ───────────────────────────────────────────────────────────────
INSERT INTO stores
  (store_id, address, category, created_at, img_url, store_name,
   stores_status, tax_id_number, updated_at, owner_id)
SELECT n, CONCAT('서울시 테스트구 ', n, '로'), 'CAFE', @NOW, '', CONCAT('매장', n),
       'ACTIVE', LPAD(n, 10, '0'), @NOW, n
FROM _seed_seq WHERE n BETWEEN 1 AND @STORES;

-- ── 5. 카테고리 (매장당 1개) ──────────────────────────────────────────────
INSERT INTO categories
  (category_id, category_name, created_at, display_order, updated_at, parent_id, store_id)
SELECT n, '메인', @NOW, 1, @NOW, NULL, n
FROM _seed_seq WHERE n BETWEEN 1 AND @STORES;

-- ── 6. 메뉴 (매장당 5개) ──────────────────────────────────────────────────
--    menu_id = (store_id - 1) * MENUS_PER_STORE + k   ← k6 common.js 의 randomMenuId 와 동일
INSERT INTO menus
  (menu_id, active, created_at, display_order, image_url, menu_name, price,
   sold_out, updated_at, category_id, store_id)
SELECT (s.n - 1) * @MENUS_PER_STORE + k.n, b'1', @NOW, k.n, '',
       CONCAT('메뉴', (s.n - 1) * @MENUS_PER_STORE + k.n), 3000 + k.n * 500,
       b'0', @NOW, s.n, s.n
FROM _seed_seq s
CROSS JOIN _seed_seq k
WHERE s.n BETWEEN 1 AND @STORES AND k.n BETWEEN 1 AND @MENUS_PER_STORE;

-- ── 7. 지갑 (개인, 고객과 1:1) ────────────────────────────────────────────
INSERT INTO wallets (wallet_id, created_at, updated_at, wallet_type, customer_id, group_id)
SELECT n, @NOW, @NOW, 'INDIVIDUAL', n, NULL
FROM _seed_seq WHERE n BETWEEN 1 AND @CUSTOMERS;

-- ── 8. 충전 거래 (지갑 × 매장) ────────────────────────────────────────────
--    lot 의 origin_charge_tx_id 가 NOT NULL 이라 반드시 먼저 있어야 한다
INSERT INTO transactions
  (transaction_id, amount, created_at, transaction_type, transaction_unique_no,
   customer_id, store_id, wallet_id)
SELECT (w.n - 1) * @STORES + s.n, @BALANCE, @NOW, 'CHARGE',
       CONCAT('SEED-', w.n, '-', s.n), w.n, s.n, w.n
FROM _seed_seq w
CROSS JOIN _seed_seq s
WHERE w.n BETWEEN 1 AND @CUSTOMERS AND s.n BETWEEN 1 AND @STORES;

-- ── 9. LOT (충전 덩어리) ──────────────────────────────────────────────────
--    lot_status enum 은 ACTIVE / CANCELED 뿐이다 (EXPIRED 없음).
--    만료 처리는 expired_settled_at + expired_amount + amount_remaining=0 방식.
INSERT INTO wallet_store_lot
  (lot_id, acquired_at, amount_remaining, amount_total, expired_at,
   lot_status, source_type, origin_charge_tx_id, store_id, wallet_id)
SELECT (w.n - 1) * @STORES + s.n, @NOW, @BALANCE, @BALANCE,
       DATE_ADD(@NOW, INTERVAL 1 YEAR), 'ACTIVE', 'CHARGE',
       (w.n - 1) * @STORES + s.n, s.n, w.n
FROM _seed_seq w
CROSS JOIN _seed_seq s
WHERE w.n BETWEEN 1 AND @CUSTOMERS AND s.n BETWEEN 1 AND @STORES;

-- ── 10. 잔액 (= SUM(lot.amount_remaining)) ────────────────────────────────
INSERT INTO wallet_store_balances (balance_id, balance, updated_at, store_id, wallet_id)
SELECT (w.n - 1) * @STORES + s.n, @BALANCE, @NOW, s.n, w.n
FROM _seed_seq w
CROSS JOIN _seed_seq s
WHERE w.n BETWEEN 1 AND @CUSTOMERS AND s.n BETWEEN 1 AND @STORES;

DROP TABLE _seed_seq;

SET FOREIGN_KEY_CHECKS = 1;
SET UNIQUE_CHECKS = 1;

-- ── 검증 ──────────────────────────────────────────────────────────────────
SELECT '=== 행 수 ===' AS '';
SELECT 'customers' t, COUNT(*) n FROM customers
UNION ALL SELECT 'customer_pin_auth', COUNT(*) FROM customer_pin_auth
UNION ALL SELECT 'owners',   COUNT(*) FROM owners
UNION ALL SELECT 'stores',   COUNT(*) FROM stores
UNION ALL SELECT 'categories', COUNT(*) FROM categories
UNION ALL SELECT 'menus',    COUNT(*) FROM menus
UNION ALL SELECT 'wallets',  COUNT(*) FROM wallets
UNION ALL SELECT 'transactions', COUNT(*) FROM transactions
UNION ALL SELECT 'wallet_store_lot', COUNT(*) FROM wallet_store_lot
UNION ALL SELECT 'wallet_store_balances', COUNT(*) FROM wallet_store_balances;

SELECT '=== 불변식: SUM(lot) == balance 가 깨진 건수 (0 이어야 함) ===' AS '';
SELECT COUNT(*) AS mismatched
FROM (
  SELECT b.wallet_id, b.store_id, b.balance,
         COALESCE(SUM(CASE WHEN l.lot_status = 'ACTIVE' AND l.expired_at > NOW()
                           THEN l.amount_remaining ELSE 0 END), 0) AS lot_sum
  FROM wallet_store_balances b
  LEFT JOIN wallet_store_lot l
         ON l.wallet_id = b.wallet_id AND l.store_id = b.store_id
  GROUP BY b.wallet_id, b.store_id, b.balance
) x
WHERE x.balance <> x.lot_sum;
