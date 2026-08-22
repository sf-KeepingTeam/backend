-- 구성 1(합침)에서 MySQL 한 대에 논리 DB 두 개를 만든다.
-- 구성 2에서는 각각 별도 MySQL 인스턴스의 기본 DB로 존재한다.
CREATE DATABASE IF NOT EXISTS ssafy_fintech_db
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS payment_service
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
