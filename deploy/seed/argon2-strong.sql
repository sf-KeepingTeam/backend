-- ═══════════════════════════════════════════════════════════════════════════
--  A2 측정 종료 후 — PIN 해시를 운영값 Argon2 로 원복한다
--  (argon2-weak.sql 을 돌렸다면 반드시 이걸로 되돌린다)
--
--      운영값  m=8192KB, t=3, p=1   ← PasswordConfig 의 new Argon2PasswordEncoder(16,32,1,1<<13,3)
--      평문 PIN "123456"
-- ═══════════════════════════════════════════════════════════════════════════

USE ssafy_fintech_db;

UPDATE customer_pin_auth
SET pin_hash = '$argon2id$v=19$m=8192,t=3,p=1$S2VlcGluZ0xvYWRUZXN0IQ$KkraKi3ns5dMOAqWE8yRVTghUtaww6CZuT0iEALZwl0',
    failed_count = 0,
    locked_until = NULL;

SELECT SUBSTRING_INDEX(SUBSTRING_INDEX(pin_hash, '$', 4), '$', -1) AS params,
       COUNT(*) AS cnt
FROM customer_pin_auth
GROUP BY params;
