CREATE TABLE `customers` (
  `customer_id`       BIGINT        NOT NULL AUTO_INCREMENT,
  `provider_id`       VARCHAR(100)  NOT NULL,
  `provider_type`     ENUM('KAKAO','GOOGLE') NOT NULL,
  `email`             VARCHAR(250)  NOT NULL,
  `phone_number`      VARCHAR(50)   NOT NULL,
  `birth`             DATE          NOT NULL,
  `name`              VARCHAR(50)   NOT NULL,
  `gender`            ENUM('MALE','FEMALE') NOT NULL,
  `img_url`           VARCHAR(200)  NOT NULL,
  `created_at`        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                      ON UPDATE CURRENT_TIMESTAMP(3),
  `deleted_at`        DATETIME(3)   NULL,
  PRIMARY KEY (`customer_id`),

  UNIQUE KEY `uq_customers_provider` (`provider_type`, `provider_id`),
  UNIQUE KEY `uq_customers_email`    (`email`),
  UNIQUE KEY `uq_customers_phone`    (`phone_number`),
  UNIQUE KEY `uq_customers_userKey`  (`user_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `owners` (
  `owner_id`          BIGINT        NOT NULL AUTO_INCREMENT,
  `provider_id`       VARCHAR(100)  NOT NULL,
  `provider_type`     ENUM('KAKAO','GOOGLE') NOT NULL,
  `email`             VARCHAR(250)  NOT NULL,
  `phone_number`      VARCHAR(50)   NOT NULL,
  `birth`             DATE          NOT NULL,
  `name`              VARCHAR(50)   NOT NULL,
  `gender`            ENUM('MALE','FEMALE') NOT NULL,
  `img_url`           VARCHAR(200)  NOT NULL,
  `created_at`        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                      ON UPDATE CURRENT_TIMESTAMP(3),
  `phone_verified_at` DATETIME(3)   NULL,
  `deleted_at`        DATETIME(3)   NULL,
  `user_key`           VARCHAR(200)  NULL,
  PRIMARY KEY (`owner_id`),

  UNIQUE KEY `uq_owners_provider` (`provider_type`, `provider_id`),
  UNIQUE KEY `uq_owners_email`    (`email`),
  UNIQUE KEY `uq_owners_phone`    (`phone_number`),
  UNIQUE KEY `uq_owners_userKey`  (`user_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `customer_pin_auth` (
  `customer_id`   BIGINT       NOT NULL,              -- 고객 고유번호 (PK & FK)
  `pin_hash`      VARCHAR(255) NOT NULL,              -- 고객 PIN 해시값 (평문 금지)
  `failed_count`  INT          NOT NULL DEFAULT 0,    -- 연속 실패 횟수
  `locked_until`  DATETIME(3)  NULL,                  -- 이 시각까지 PIN 시도 금지 (쿨다운 종료 시각)
  `set_at`        DATETIME(3)  NOT NULL,              -- 현재 PIN(해시) 설정 시각
  `updated_at`    DATETIME(3)  NOT NULL,              -- 마지막으로 변경된 시각
  `last_verify_at` DATETIME(3) NULL,                  -- 마지막으로 PIN 검증에 성공한 시각

  PRIMARY KEY (`customer_id`),
  CONSTRAINT `fk_customer_pin_auth_customer`
    FOREIGN KEY (`customer_id`) REFERENCES `customers`(`customer_id`)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `groups` (
  `group_id`          BIGINT        NOT NULL AUTO_INCREMENT,
  `group_name`        VARCHAR(100)  NOT NULL,
  `group_code`        VARCHAR(100)  NOT NULL,
  `group_description` VARCHAR(150)  NOT NULL,
  `created_at`        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                        ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`group_id`),
  UNIQUE KEY `uq_groups_code` (`group_code`)    -- 코드 중복 방지
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `group_members` (
  `group_member_id` BIGINT       NOT NULL AUTO_INCREMENT,
  `group_id`        BIGINT       NOT NULL,          -- 모임 FK
  `customer_id`     BIGINT       NOT NULL,          -- 고객 FK
	`leader`          TINYINT(1)       NOT NULL DEFAULT 0,-- 모임장 여부
  `created_at`      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                   ON UPDATE CURRENT_TIMESTAMP(3),

  PRIMARY KEY (`group_member_id`),

  CONSTRAINT `fk_group_members_group`
    FOREIGN KEY (`group_id`) REFERENCES `groups`(`group_id`)
    ON DELETE CASCADE,

  CONSTRAINT `fk_group_members_customer`
    FOREIGN KEY (`customer_id`) REFERENCES `customers`(`customer_id`)
    ON DELETE CASCADE,

  UNIQUE KEY `uq_group_member` (`group_id`, `customer_id`),
  
  KEY `idx_customer_group` (`customer_id`,`group_id`),
  KEY `idx_group_leader`   (`group_id`,`leader`)
  
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `group_add_requests` (
  `group_add_requests_id` BIGINT       NOT NULL AUTO_INCREMENT,
  `group_id`              BIGINT       NOT NULL,
  `customer_id`           BIGINT       NOT NULL,
  `status`                ENUM('PENDING','ACCEPT','REJECT') NOT NULL DEFAULT 'PENDING',
  `created_at`             DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`            DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                   ON UPDATE CURRENT_TIMESTAMP(3),

  PRIMARY KEY (`group_add_requests_id`),

  -- FK 설정
  CONSTRAINT `fk_group_add_requests_group`
    FOREIGN KEY (`group_id`) REFERENCES `groups`(`group_id`)
    ON DELETE CASCADE,

  CONSTRAINT `fk_group_add_requests_customer`
    FOREIGN KEY (`customer_id`) REFERENCES `customers`(`customer_id`)
    ON DELETE CASCADE,
    
  KEY `idx_user_group_status_created_at` (`customer_id`,`group_id`,`status`,`created_at`),
  KEY `idx_group_status_created_at`      (`group_id`,`status`,`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `stores` (
  `store_id`       BIGINT        NOT NULL AUTO_INCREMENT,
  `owner_id`       BIGINT        NOT NULL,
  `description`    VARCHAR(250)  NULL,
  `tax_id_number`  VARCHAR(50)   NOT NULL,
  `address`        VARCHAR(100)  NOT NULL,
  `store_name`     VARCHAR(100)  NOT NULL,
  `phone_number`   VARCHAR(100)  NULL,
  `merchant_id`    BIGINT        NOT NULL,   -- SSAFY 금융망 내 등록된 가맹점ID
  `img_url`        VARCHAR(200)  NOT NULL,
  `category`       VARCHAR(50)   NOT NULL,
  `bank_account`   VARCHAR(100)  NOT NULL,
  `stores_status`  ENUM('ACTIVE','SUSPENDED','DELETED') NOT NULL,
  `created_at`     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                    ON UPDATE CURRENT_TIMESTAMP(3),
  `deleted_at`     DATETIME(3)   NULL,

  PRIMARY KEY (`store_id`),

  CONSTRAINT `fk_stores_owner`
    FOREIGN KEY (`owner_id`) REFERENCES `owners`(`owner_id`)
    ON DELETE RESTRICT ON UPDATE CASCADE,

  UNIQUE KEY `uq_stores_tax_id`   (`tax_id_number`),
  UNIQUE KEY `uq_stores_merchant` (`merchant_id`),
  UNIQUE KEY `uk_store_tax_addr` (`tax_id_number`,`address`),
  UNIQUE KEY `uq_owner_store_name`(`owner_id`, `store_name`), -- 한 사업자 내 중복 가게명 방지
  KEY `idx_stores_owner`   (`owner_id`),
  KEY `idx_stores_category`(`category`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `store_favorite` (
  `favorite_id`     BIGINT       NOT NULL AUTO_INCREMENT,
  `customer_id`     BIGINT       NOT NULL,
  `store_id`        BIGINT       NOT NULL,
  `active`          TINYINT(1)   NOT NULL DEFAULT 1,       				-- 찜 여부 (1=찜, 0=해제)
  `favorited_at`    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3), -- 찜 시각
  `unfavorited_at`  DATETIME(3)  NULL,                    				-- 해제 시각

  PRIMARY KEY (`favorite_id`),

  UNIQUE KEY `uq_customer_store` (`customer_id`, `store_id`),

  CONSTRAINT `fk_store_favorite_customer`
    FOREIGN KEY (`customer_id`) REFERENCES `customers`(`customer_id`)
    ON DELETE CASCADE,

  CONSTRAINT `fk_store_favorite_store`
    FOREIGN KEY (`store_id`) REFERENCES `stores`(`store_id`)
    ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `categories` (
  `category_id`    BIGINT        NOT NULL AUTO_INCREMENT,
  `parent_id`      BIGINT        NULL,                                    -- 자기참조 FK
  `store_id`       BIGINT        NOT NULL,
  `category_name`  VARCHAR(100)  NOT NULL,
  `display_order`  INT           NOT NULL,                                -- 동일 부모 내 노출 순서
  `created_at`     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                     ON UPDATE CURRENT_TIMESTAMP(3),

  PRIMARY KEY (`category_id`),

  CONSTRAINT `fk_categories_store`
    FOREIGN KEY (`store_id`)  REFERENCES `stores`(`store_id`)
    ON DELETE CASCADE,

  CONSTRAINT `fk_categories_parent`
    FOREIGN KEY (`parent_id`) REFERENCES `categories`(`category_id`)
    ON DELETE SET NULL,                                                    -- 부모 삭제 시 자식은 최상위로 승격

  UNIQUE KEY `uq_cat_name_per_parent` (`store_id`, `parent_id`, `category_name`), 
  UNIQUE KEY `uq_cat_order_per_parent`(`store_id`, `parent_id`, `display_order`),

  KEY `idx_categories_store` (`store_id`),
  KEY `idx_categories_parent`(`parent_id`),
  KEY `idx_cat_store_parent_order` (`store_id`,`parent_id`,`display_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `menus` (
  `menu_id`       BIGINT        NOT NULL AUTO_INCREMENT,
  `category_id`   BIGINT        NOT NULL,
  `store_id`      BIGINT        NOT NULL,
  `menu_name`     VARCHAR(150)  NOT NULL,
  `price`         INT           NOT NULL,
  `description`   VARCHAR(500)  NULL,
  `image_url`     VARCHAR(500)  NOT NULL,
  `sold_out`      TINYINT(1)    NOT NULL DEFAULT 0,
  `active`        TINYINT(1)    NOT NULL DEFAULT 1,
  `display_order` INT           NOT NULL,                            -- 카테고리 내 노출 순서
  `created_at`    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                   ON UPDATE CURRENT_TIMESTAMP(3),
  `deleted_at`    DATETIME(3)   NULL,                                -- 소프트 삭제

  PRIMARY KEY (`menu_id`),

  CONSTRAINT `fk_menus_store`
    FOREIGN KEY (`store_id`) REFERENCES `stores`(`store_id`)
    ON DELETE CASCADE,

  CONSTRAINT `fk_menus_category`
    FOREIGN KEY (`category_id`) REFERENCES `categories`(`category_id`)
    ON DELETE RESTRICT,                                              -- 카테고리 삭제 전 재배치 강제

  # UNIQUE KEY `uq_menu_name_per_store` (`store_id`, `menu_name`),
  UNIQUE KEY `uq_order_per_category`  (`store_id`, `category_id`, `display_order`),

  KEY `idx_menus_store`    (`store_id`),
  KEY `idx_menus_category` (`category_id`),
  KEY `idx_menus_active`   (`active`),
  KEY `idx_menus_soldout`  (`sold_out`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `wallets` (
  `wallet_id`   BIGINT      NOT NULL AUTO_INCREMENT, 
  `group_id`    BIGINT      NULL,                                -- 모임 지갑일 경우 참조
  `customer_id` BIGINT      NULL,                                -- 개인 지갑일 경우 참조
  `wallet_type` ENUM('INDIVIDUAL','GROUP') NOT NULL,			 -- 지갑 유형
  `created_at`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                               ON UPDATE CURRENT_TIMESTAMP(3),

  PRIMARY KEY (`wallet_id`),

  CONSTRAINT `fk_wallets_group`
    FOREIGN KEY (`group_id`) REFERENCES `groups`(`group_id`)
    ON DELETE CASCADE,

  CONSTRAINT `fk_wallets_customer`
    FOREIGN KEY (`customer_id`) REFERENCES `customers`(`customer_id`)
    ON DELETE CASCADE,

  UNIQUE KEY `uq_wallets_customer` (`customer_id`, `wallet_type`),
  UNIQUE KEY `uq_wallets_group`    (`group_id`, `wallet_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `wallet_store_balances` (
  `balance_id` BIGINT        NOT NULL AUTO_INCREMENT,
  `wallet_id`  BIGINT        NOT NULL,
  `store_id`   BIGINT        NOT NULL,
  `balance`    BIGINT UNSIGNED NOT NULL DEFAULT 0.00,     -- 카드 잔액
  `updated_at` DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                               ON UPDATE CURRENT_TIMESTAMP(3),

  PRIMARY KEY (`balance_id`),

  CONSTRAINT `fk_wallet_store_wallet`
    FOREIGN KEY (`wallet_id`) REFERENCES `wallets`(`wallet_id`)
    ON DELETE CASCADE,

  CONSTRAINT `fk_wallet_store_store`
    FOREIGN KEY (`store_id`) REFERENCES `stores`(`store_id`)
    ON DELETE CASCADE,

  UNIQUE KEY `uq_wallet_store` (`wallet_id`, `store_id`),

  KEY `idx_wallet_store_wallet` (`wallet_id`),
  KEY `idx_wallet_store_store`  (`store_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `transactions` (
  `transaction_id`      BIGINT         NOT NULL AUTO_INCREMENT,
  `wallet_id`           BIGINT         NOT NULL,                        -- 거래가 발생한 지갑
  `related_wallet_id`   BIGINT         NULL,                            -- 지갑 간 공유/회수 시 상대 지갑
  `customer_id`         BIGINT         NOT NULL,                        -- 거래 주체(고객)
  `store_id`            BIGINT         NOT NULL,                        -- 가게(충전/사용이 귀속되는 상점)
  `transaction_type`    ENUM('CHARGE','USE','TRANSFER_IN','TRANSFER_OUT','CANCEL_CHARGE','CANCEL_USE') NOT NULL,
  `amount`              BIGINT UNSIGNED  NOT NULL,                        -- 거래 금액(양수 권장)
  `created_at`          DATETIME(3)    NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `transaction_unique_no` VARCHAR(50)    NULL,                            -- 가상은행망 결제 고유번호(충전만 사용)
  `ref_tx_id`           BIGINT         NULL,      						-- 취소 시 원거래 참조(CHARGE 또는 USE)

  PRIMARY KEY (`transaction_id`),

  CONSTRAINT `fk_tx_wallet`         FOREIGN KEY (`wallet_id`)          REFERENCES `wallets`(`wallet_id`),
  CONSTRAINT `fk_tx_related_wallet` FOREIGN KEY (`related_wallet_id`)  REFERENCES `wallets`(`wallet_id`),
  CONSTRAINT `fk_tx_customer`       FOREIGN KEY (`customer_id`)        REFERENCES `customers`(`customer_id`),
  CONSTRAINT `fk_tx_store`          FOREIGN KEY (`store_id`)           REFERENCES `stores`(`store_id`),
  CONSTRAINT `fk_tx_ref_tx`         FOREIGN KEY (`ref_tx_id`)          REFERENCES `transactions`(`transaction_id`),
    
  -- 타입이 CHARGE라면 transactionUniqueNo는 NULL일 수 없다는 뜻
  CHECK (`transaction_type` <> 'CHARGE' OR `transaction_unique_no` IS NOT NULL),
  CHECK (`amount` > 0),
  
  -- 공유/회수(TRANSFER_*)일 때만 related_wallet_id 필요
  CHECK (
    (`transaction_type` IN ('TRANSFER_IN','TRANSFER_OUT') AND `related_wallet_id` IS NOT NULL) OR
    (`transaction_type` NOT IN ('TRANSFER_IN','TRANSFER_OUT') AND `related_wallet_id` IS NULL)
  ),
  
  -- 취소 거래는 반드시 원거래를 참조해야 함
  CHECK (
    (`transaction_type` IN ('CANCEL_CHARGE','CANCEL_USE') AND `ref_tx_id` IS NOT NULL) OR
    (`transaction_type` NOT IN ('CANCEL_CHARGE','CANCEL_USE') AND `ref_tx_id` IS NULL)
  ),

  UNIQUE KEY `uq_tx_id_store` (`transaction_id`, `store_id`),
  UNIQUE KEY `uq_charge_unique` (`transaction_type`, `transaction_unique_no`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `wallet_store_lot` (
  `lot_id`               BIGINT         NOT NULL AUTO_INCREMENT,     -- PK
  `wallet_id`            BIGINT         NOT NULL,                    -- 대상 지갑
  `store_id`             BIGINT         NOT NULL,                    -- 대상 가게
  `amount_total`         BIGINT UNSIGNED  NOT NULL,                    -- 생성 시 총량
  `amount_remaining`     BIGINT UNSIGNED  NOT NULL,                    -- 남은 양
  `acquired_at`          DATETIME(3)    NOT NULL,                    -- 충전/공유 시각
  `expired_at`           DATETIME(3)    NOT NULL,                    -- 유효기간
  `source_type`          ENUM('CHARGE','TRANSFER_IN') NOT NULL,
  `contributor_wallet_id` BIGINT        NULL,                        -- 공유 시: 제공 지갑
  `origin_charge_tx_id`  BIGINT         NOT NULL,                    -- 원천 CHARGE 트랜잭션
  
    -- 취소 처리(논리 종료)
  `lot_status`            ENUM('ACTIVE','CANCELED') NOT NULL DEFAULT 'ACTIVE',
  `canceled_at`           DATETIME(3)    NULL,
  `cancel_tx_id`          BIGINT         NULL,
  
  PRIMARY KEY (`lot_id`),

  CONSTRAINT `fk_lot_wallet`
    FOREIGN KEY (`wallet_id`) REFERENCES `wallets`(`wallet_id`)
    ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT `fk_lot_store`
    FOREIGN KEY (`store_id`)  REFERENCES `stores`(`store_id`)
    ON DELETE RESTRICT ON UPDATE CASCADE,
  CONSTRAINT `fk_lot_contributor_wallet`
    FOREIGN KEY (`contributor_wallet_id`) REFERENCES `wallets`(`wallet_id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_lot_origin_charge_tx`
    FOREIGN KEY (`origin_charge_tx_id`) REFERENCES `transactions`(`transaction_id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_lot_cancel_tx`
    FOREIGN KEY (`cancel_tx_id`)          REFERENCES `transactions`(`transaction_id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,

  CHECK (`amount_total` >= 0 AND `amount_remaining` >= 0
         AND `amount_remaining` <= `amount_total`),
  CHECK (`expired_at` > `acquired_at`),
  -- 공유일 땐 contributor 지갑 필수 / 충전일 땐 NULL 이어야 함
  CHECK (
    (`source_type` = 'TRANSFER_IN' AND `contributor_wallet_id` IS NOT NULL) OR
    (`source_type` = 'CHARGE'      AND `contributor_wallet_id` IS NULL)
  ),
  CHECK (
    (`lot_status`='ACTIVE'   AND `canceled_at` IS NULL AND `cancel_tx_id` IS NULL) OR
    (`lot_status`='CANCELED' AND `canceled_at` IS NOT NULL
                              AND `cancel_tx_id` IS NOT NULL
                              AND `amount_remaining`=0)
  ),
  -- CHARGE 원장은 원천 결제( origin_charge_tx_id )당 1개만 존재(부분 유니크)
  `charge_origin_key` BIGINT
      GENERATED ALWAYS AS (
        CASE WHEN `source_type`='CHARGE' THEN `origin_charge_tx_id` ELSE NULL END
      ) STORED,
      
  UNIQUE KEY `uq_charge_origin` (`charge_origin_key`),

  KEY `idx_lot_wallet_store` (`wallet_id`,`store_id`),
  KEY `idx_lot_origin_tx` (`origin_charge_tx_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `wallet_lot_moves` (
  `move_id`        BIGINT        NOT NULL AUTO_INCREMENT,
  `transaction_id` BIGINT        NOT NULL,                     -- USE 또는 CANCELED 등
  `lot_id`         BIGINT        NOT NULL,
  `delta`          BIGINT        NOT NULL,                     -- USE: 음수, 취소: 양수
  `created_at`     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`move_id`),
  CONSTRAINT `fk_moves_tx`  FOREIGN KEY (`transaction_id`) REFERENCES `transactions`(`transaction_id`),
  CONSTRAINT `fk_moves_lot` FOREIGN KEY (`lot_id`)         REFERENCES `wallet_store_lot`(`lot_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `qr_token` (
  `qr_token_id`   BINARY(16)  NOT NULL,  -- UUIDv7 (시간 순 배치)
  `customer_id`   BIGINT      NOT NULL,
  `wallet_id`     BIGINT      NOT NULL, 
  `bind_store_id` BIGINT      NOT NULL,
  `mode`          ENUM('CPQR','MPQR','REFUND') NOT NULL,
  `expires_at`    DATETIME(3) NOT NULL,  -- 만료 시각
  `state`         ENUM('ISSUED','CONSUMED','EXPIRED','REVOKED') NOT NULL DEFAULT 'ISSUED',                                  -- 상태
  `created_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `consumed_at`   DATETIME(3) NULL,  -- 처리 시각(소비/만료/회수)

  PRIMARY KEY (`qr_token_id`),

  CONSTRAINT `fk_qr_token_customer`
    FOREIGN KEY (`customer_id`)   REFERENCES `customers`(`customer_id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_qr_token_wallet`
    FOREIGN KEY (`wallet_id`)     REFERENCES `wallets`(`wallet_id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_qr_token_store`
    FOREIGN KEY (`bind_store_id`) REFERENCES `stores`(`store_id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,

  CHECK (`expires_at` > `created_at`),
  CHECK (
    (`state`='ISSUED' AND `consumed_at` IS NULL) OR
    (`state` IN ('CONSUMED','EXPIRED','REVOKED') AND `consumed_at` IS NOT NULL)
  ),

  KEY idx_state_expires (state, expires_at), -- 청소용
  KEY `idx_wallet_state`  (`wallet_id`, `state`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `payment_intent` (
  `version`        BIGINT       NOT NULL DEFAULT 0,                        -- 낙관적 락(UPDATE 시 애플리케이션이 +1)
  `intent_id`      BIGINT       NOT NULL AUTO_INCREMENT,                   -- PK
  `public_id`      BINARY(16)   NOT NULL,                                  -- 외부 노출용 UUID (v7)
  `qr_token_id`    BINARY(16)   NOT NULL,                                  -- qr_token.qr_token_id
  `customer_id`    BIGINT       NOT NULL,                                  -- customers.customer_id
  `wallet_id`      BIGINT       NOT NULL,
  `store_id`       BIGINT       NOT NULL,                                  -- stores.store_id
  `amount`         BIGINT       NOT NULL,                                  -- KRW 원단위(소수점 없음)
  `status`         ENUM('PENDING','APPROVED','DECLINED','CANCELED','COMPLETED','EXPIRED')
                   NOT NULL DEFAULT 'PENDING',
  `created_at`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  `expires_at`     DATETIME(3)  NOT NULL,
  `approved_at`    DATETIME(3)  NULL,
  `declined_at`    DATETIME(3)  NULL,
  `canceled_at`    DATETIME(3)  NULL,
  `completed_at`   DATETIME(3)  NULL,
  `idempotency_key` VARCHAR(64) NULL,                                      -- 최초 생성 멱등성 키

  PRIMARY KEY (`intent_id`),

  UNIQUE KEY `uk_intent_qr_token`  (`qr_token_id`),                        -- 같은 QR로 의도 1건만
  UNIQUE KEY `uk_intent_public_id` (`public_id`),
  UNIQUE KEY `uk_intent_idem`      (`idempotency_key`),

  KEY `idx_status_expires` (`status`, `expires_at`),
  KEY `idx_store_status`   (`store_id`, `status`),
  KEY `idx_wallet_status`  (`wallet_id`, `status`),

  CONSTRAINT `fk_intent_qr`
    FOREIGN KEY (`qr_token_id`) REFERENCES `qr_token`(`qr_token_id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_intent_customer`
    FOREIGN KEY (`customer_id`)  REFERENCES `customers`(`customer_id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_intent_wallet`
    FOREIGN KEY (`wallet_id`)    REFERENCES `wallets`(`wallet_id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,
  CONSTRAINT `fk_intent_store`
    FOREIGN KEY (`store_id`)     REFERENCES `stores`(`store_id`)
    ON DELETE RESTRICT ON UPDATE RESTRICT,

  CHECK (`amount` >= 0),
  CHECK (`expires_at` > `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `payment_intent_item` (
  `id`              BIGINT        NOT NULL AUTO_INCREMENT,
  `intent_id`       BIGINT        NOT NULL,
  `menu_id`         BIGINT        NOT NULL,                 -- 당시 메뉴 PK
  `menu_name_snap`  VARCHAR(100)  NOT NULL,                 -- 메뉴명 스냅샷
  `unit_price_snap` BIGINT        NOT NULL,
  `quantity`        INT           NOT NULL,
  `line_total`      BIGINT        AS (`unit_price_snap` * `quantity`) STORED,
  PRIMARY KEY (`id`),

  KEY `idx_intent` (`intent_id`),

  CONSTRAINT `fk_item_intent`
    FOREIGN KEY (`intent_id`) REFERENCES `payment_intent`(`intent_id`)
      ON DELETE CASCADE,

  CHECK (`unit_price_snap` >= 0),
  CHECK (`quantity` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `idempotency_keys` (
  `id`               BIGINT        NOT NULL AUTO_INCREMENT,
  `key_uuid`         BINARY(16)    NOT NULL,
  `actor_type`       ENUM('MERCHANT','CUSTOMER','SYSTEM') NOT NULL,
  `actor_id`         BIGINT        NOT NULL,
  `method`           VARCHAR(10)   NOT NULL,                                   -- POST/PUT/...
  `path`             VARCHAR(255)  NOT NULL,                                   -- API 경로
  `body_hash`        VARBINARY(32) NOT NULL,                                   -- 정규화 바디
  `status`           ENUM('IN_PROGRESS','DONE') NOT NULL DEFAULT 'IN_PROGRESS',
  `http_status`      INT   		   NULL,                                       -- 최초 응답 코드
  `response_json`    JSON          NULL,                                       -- 최초 응답 원문(리플레이)
  `intent_public_id` BINARY(16)    NULL,                                       -- 관련 리소스 공개 ID
  `created_at`       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_idem_scope` (`actor_type`,`actor_id`,`path`,`key_uuid`),
  KEY `idx_idem_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `settlement_tasks` (
  `task_id`        BIGINT        NOT NULL AUTO_INCREMENT,
  `transaction_id` BIGINT        NOT NULL,
  `status`         ENUM('PENDING','COMPLETED','FAILED','CANCELED','LOCKED')
                   NOT NULL DEFAULT 'PENDING',
  `processed_at`   DATETIME(3)   NULL,  -- 완료/실패/취소 시각
  `created_at`     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`     DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                                      ON UPDATE CURRENT_TIMESTAMP(3),

  PRIMARY KEY (`task_id`),

  UNIQUE KEY `uk_task_tx` (`transaction_id`),

  KEY `idx_status_created` (`status`, `created_at`),

  CONSTRAINT `fk_task_tx`
    FOREIGN KEY (`transaction_id`) REFERENCES `transactions`(`transaction_id`)
    ON DELETE RESTRICT ON UPDATE CASCADE,

  CHECK (
    (`status` IN ('PENDING','LOCKED') AND `processed_at` IS NULL) OR
    (`status` IN ('COMPLETED','FAILED','CANCELED') AND `processed_at` IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `transaction_items` (
  `item_id`               BIGINT        NOT NULL AUTO_INCREMENT,
  `transaction_id`        BIGINT        NOT NULL,
  `store_id`              BIGINT        NOT NULL,
  `menu_id`               BIGINT        NULL,
  `menu_name_snapshot`    VARCHAR(150)  NOT NULL,
  `menu_price_snapshot`   BIGINT        NOT NULL,
  `quantity`              INT           NOT NULL,
  `line_total`            BIGINT        AS (`menu_price_snapshot` * `quantity`) STORED,
  `created_at`            DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

  PRIMARY KEY (`item_id`),

  CONSTRAINT `fk_ti_tx_store`
    FOREIGN KEY (`transaction_id`, `store_id`)
    REFERENCES `transactions`(`transaction_id`, `store_id`)
    ON DELETE CASCADE,

  CONSTRAINT `fk_ti_menu`
    FOREIGN KEY (`menu_id`) REFERENCES `menus`(`menu_id`)
    ON DELETE SET NULL,

  KEY `idx_ti_tx` (`transaction_id`),

  CHECK (`quantity` > 0),
  CHECK (`menu_price_snapshot` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `notifications` (
    `notification_id` BIGINT NOT NULL AUTO_INCREMENT,
    `customer_id` BIGINT NULL,
    `owner_id` BIGINT NULL,
    `content` VARCHAR(500) NOT NULL,
	`url` VARCHAR(255) NULL,
    `is_read` TINYINT(1) NOT NULL,
    `notification_type` ENUM('ORDER','EVENT','ETC') NOT NULL,
    `created_at` DATETIME NOT NULL,
    PRIMARY KEY (`notification_id`),

    -- FK: notifications → customers (N:1)
    CONSTRAINT `fk_notification_customer`
        FOREIGN KEY (`customer_id`)
        REFERENCES `customers` (`customer_id`)
        ON DELETE CASCADE,

    -- FK: notifications → owners (N:1)
    CONSTRAINT `fk_notification_owner`
        FOREIGN KEY (`owner_id`)
        REFERENCES `owners` (`owner_id`)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE `fcm_tokens` (
    `fcm_token_id` BIGINT NOT NULL AUTO_INCREMENT,
    `customer_id` BIGINT NOT NULL,
    `owner_id` BIGINT NOT NULL,
    `token` VARCHAR(500) NOT NULL,
    `created_at` DATETIME NOT NULL,
    `updated_at` DATETIME NULL,
    PRIMARY KEY (`fcm_token_id`),

    -- FK: fcm_token → customers (N:1)
    CONSTRAINT `fk_fcm_token_customer`
        FOREIGN KEY (`customer_id`)
        REFERENCES `customers` (`customer_id`)
        ON DELETE CASCADE,

    -- FK: fcm_token → owners (N:1)
    CONSTRAINT `fk_fcm_token_owner`
        FOREIGN KEY (`owner_id`)
        REFERENCES `owners` (`owner_id`)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
