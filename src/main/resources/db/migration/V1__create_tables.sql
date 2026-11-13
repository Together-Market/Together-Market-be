-- Initial schema. Applied migrations must not be edited.
CREATE TABLE `users` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '사용자 ID',
  `email` VARCHAR(255) NOT NULL COMMENT '이메일',
  `password_hash` VARCHAR(255) NOT NULL COMMENT '비밀번호 해시',
  `name` VARCHAR(100) NOT NULL COMMENT '사용자명',
  `role` VARCHAR(20) NOT NULL COMMENT '사용자 역할',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_users_1` (`email`),
  CONSTRAINT ck_users_1 CHECK (role IN ('CUSTOMER','OWNER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='사용자';

CREATE TABLE `stores` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '참여 점포 ID',
  `owner_id` BIGINT NOT NULL COMMENT '사장님 사용자 ID',
  `name` VARCHAR(150) NOT NULL COMMENT '점포명',
  `business_type` VARCHAR(100) NOT NULL COMMENT '업종명',
  `address` VARCHAR(500) NOT NULL COMMENT '주소',
  `latitude` DECIMAL(10,7) NULL COMMENT '위도',
  `longitude` DECIMAL(10,7) NULL COMMENT '경도',
  `phone` VARCHAR(30) NOT NULL COMMENT '연락처',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_stores_1` FOREIGN KEY (`owner_id`) REFERENCES `users` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='참여 점포';

CREATE TABLE `store_hours` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '일정 ID',
  `store_id` BIGINT NOT NULL COMMENT '점포 ID',
  `weekday` SMALLINT NULL COMMENT '요일',
  `target_date` DATE NULL COMMENT '특정일',
  `hour_type` VARCHAR(20) NOT NULL COMMENT '시간 종류',
  `is_closed` BOOLEAN NOT NULL COMMENT '휴무 여부',
  `opens_at` TIME NULL COMMENT '시작시간',
  `closes_at` TIME NULL COMMENT '종료시간',
  `end_day_offset` SMALLINT NOT NULL DEFAULT 0 COMMENT '종료일 가산일수',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_store_hours_1` (`store_id`, `weekday`, `hour_type`),
  UNIQUE KEY `uk_store_hours_2` (`store_id`, `target_date`, `hour_type`),
  CONSTRAINT `fk_store_hours_1` FOREIGN KEY (`store_id`) REFERENCES `stores` (`id`),
  CONSTRAINT ck_store_hours_1 CHECK ((weekday IS NULL AND target_date IS NOT NULL) OR (weekday IS NOT NULL AND target_date IS NULL)),
  CONSTRAINT ck_store_hours_2 CHECK (weekday IS NULL OR weekday BETWEEN 1 AND 7),
  CONSTRAINT ck_store_hours_3 CHECK (hour_type IN ('BUSINESS','PICKUP')),
  CONSTRAINT ck_store_hours_4 CHECK (is_closed IN (0,1)),
  CONSTRAINT ck_store_hours_5 CHECK ((is_closed = 1 AND opens_at IS NULL AND closes_at IS NULL AND end_day_offset = 0) OR (is_closed = 0 AND opens_at IS NOT NULL AND closes_at IS NOT NULL AND end_day_offset IN (0,1) AND TIME_TO_SEC(opens_at) >= 0 AND TIME_TO_SEC(opens_at) < 86400 AND TIME_TO_SEC(closes_at) >= 0 AND TIME_TO_SEC(closes_at) < 86400 AND TIME_TO_SEC(closes_at) + end_day_offset * 86400 - TIME_TO_SEC(opens_at) > 0 AND TIME_TO_SEC(closes_at) + end_day_offset * 86400 - TIME_TO_SEC(opens_at) <= 86400))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='영업·수령 일정';

CREATE TABLE `public_store_records` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '공공데이터 점포 원문 ID',
  `source` VARCHAR(50) NOT NULL COMMENT '공공데이터 출처',
  `external_id` VARCHAR(200) NULL COMMENT '원천 제공 식별자',
  `source_row_number` VARCHAR(50) NULL COMMENT '원천 목록 번호',
  `name` VARCHAR(200) NULL COMMENT '원천 점포명',
  `branch_name` VARCHAR(200) NULL COMMENT '지점명',
  `business_type` VARCHAR(150) NULL COMMENT '업종명',
  `address` VARCHAR(700) NULL COMMENT '주소',
  `latitude` DECIMAL(10,7) NULL COMMENT '위도',
  `longitude` DECIMAL(10,7) NULL COMMENT '경도',
  `last_seen_at` DATETIME NOT NULL COMMENT '최근 수집일시',
  `matched_store_id` BIGINT NULL COMMENT '연결된 참여 점포 ID',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_public_store_records_1` FOREIGN KEY (`matched_store_id`) REFERENCES `stores` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='공공데이터 점포';

CREATE TABLE `purpose_categories` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '구매 목적 분류 ID',
  `parent_id` BIGINT NULL COMMENT '상위 분류 ID',
  `name` VARCHAR(100) NOT NULL COMMENT '구매 목적명',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_purpose_categories_1` FOREIGN KEY (`parent_id`) REFERENCES `purpose_categories` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='구매 목적 분류';

CREATE TABLE `products` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '상품 ID',
  `store_id` BIGINT NOT NULL COMMENT '점포 ID',
  `name` VARCHAR(200) NOT NULL COMMENT '상품명',
  `description` TEXT NOT NULL COMMENT '설명',
  `image_url` TEXT NULL COMMENT '상품 이미지 주소',
  `price_amount` DECIMAL(12,0) NOT NULL COMMENT '상품 가격',
  `preparation_minutes` INTEGER NOT NULL COMMENT '준비시간(분)',
  `sale_status` VARCHAR(20) NOT NULL COMMENT '판매 상태',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_products_1` (`store_id`, `id`),
  CONSTRAINT `fk_products_1` FOREIGN KEY (`store_id`) REFERENCES `stores` (`id`),
  CONSTRAINT ck_products_1 CHECK (price_amount >= 0),
  CONSTRAINT ck_products_2 CHECK (preparation_minutes >= 0),
  CONSTRAINT ck_products_3 CHECK (sale_status IN ('ON_SALE','SOLD_OUT','PAUSED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='상품';

CREATE TABLE `product_purpose_tags` (
  `product_id` BIGINT NOT NULL COMMENT '상품 ID',
  `purpose_category_id` BIGINT NOT NULL COMMENT '구매 목적 분류 ID',
  PRIMARY KEY (`product_id`, `purpose_category_id`),
  CONSTRAINT `fk_product_purpose_tags_1` FOREIGN KEY (`product_id`) REFERENCES `products` (`id`),
  CONSTRAINT `fk_product_purpose_tags_2` FOREIGN KEY (`purpose_category_id`) REFERENCES `purpose_categories` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='상품 목적 태그';

CREATE TABLE `purchase_requests` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '구매 조건 요청 ID',
  `customer_id` BIGINT NOT NULL COMMENT '고객 사용자 ID',
  `purpose_category_id` BIGINT NOT NULL COMMENT '구매 목적 분류 ID',
  `budget_amount` DECIMAL(12,0) NOT NULL COMMENT '총예산',
  `preferred_pickup_at` DATETIME NOT NULL COMMENT '희망 수령일시',
  `status` VARCHAR(20) NOT NULL COMMENT '추천 처리 상태',
  `failure_code` VARCHAR(50) NULL COMMENT '추천 실패 코드',
  `created_at` DATETIME NOT NULL COMMENT '생성일시',
  `answers` JSON NOT NULL COMMENT '맞춤형 질문 답변',
  PRIMARY KEY (`id`),
  CONSTRAINT `fk_purchase_requests_1` FOREIGN KEY (`customer_id`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_purchase_requests_2` FOREIGN KEY (`purpose_category_id`) REFERENCES `purpose_categories` (`id`),
  CONSTRAINT ck_purchase_requests_1 CHECK (budget_amount > 0),
  CONSTRAINT ck_purchase_requests_2 CHECK (status IN ('RECOMMENDING','READY','FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='구매 조건·답변';

CREATE TABLE `recommendation_candidates` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '추천 조합 ID',
  `purchase_request_id` BIGINT NOT NULL COMMENT '구매 조건 요청 ID',
  `rank` INTEGER NOT NULL COMMENT '추천 순위',
  `recommendation_reason` TEXT NOT NULL COMMENT '추천 이유',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_recommendation_candidates_1` (`purchase_request_id`, `rank`),
  CONSTRAINT `fk_recommendation_candidates_1` FOREIGN KEY (`purchase_request_id`) REFERENCES `purchase_requests` (`id`),
  CONSTRAINT ck_recommendation_candidates_1 CHECK (`rank` > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='추천 조합';

CREATE TABLE `recommendation_items` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '추천 조합 구성 상품 ID',
  `recommendation_candidate_id` BIGINT NOT NULL COMMENT '추천 조합 ID',
  `product_id` BIGINT NOT NULL COMMENT '상품 ID',
  `quantity` INTEGER NOT NULL DEFAULT 1 COMMENT '수량',
  `unit_price_snapshot` DECIMAL(12,0) NOT NULL COMMENT '단가 사본',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_recommendation_items_1` (`recommendation_candidate_id`, `product_id`),
  CONSTRAINT `fk_recommendation_items_1` FOREIGN KEY (`recommendation_candidate_id`) REFERENCES `recommendation_candidates` (`id`),
  CONSTRAINT `fk_recommendation_items_2` FOREIGN KEY (`product_id`) REFERENCES `products` (`id`),
  CONSTRAINT ck_recommendation_items_1 CHECK (quantity >= 1),
  CONSTRAINT ck_recommendation_items_2 CHECK (unit_price_snapshot >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='추천 구성 상품';

CREATE TABLE `reservations` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '통합 예약 ID',
  `customer_id` BIGINT NOT NULL COMMENT '고객 사용자 ID',
  `recommendation_candidate_id` BIGINT NOT NULL COMMENT '추천 조합 ID',
  `status` VARCHAR(20) NOT NULL COMMENT '통합 예약 상태',
  `failure_code` VARCHAR(30) NULL COMMENT '예약 실패 사유 코드',
  `customer_name` VARCHAR(100) NOT NULL COMMENT '예약자명',
  `customer_phone` VARCHAR(30) NOT NULL COMMENT '예약자 연락처',
  `customer_note` TEXT NULL COMMENT '고객 요청사항',
  `approval_deadline_at` DATETIME NOT NULL COMMENT '승인 마감일시',
  `request_key` VARCHAR(100) NOT NULL COMMENT '예약 중복 방지 키',
  `request_payload_hash` CHAR(64) NOT NULL COMMENT '예약 요청 내용 해시',
  `created_at` DATETIME NOT NULL COMMENT '생성일시',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reservations_1` (`customer_id`, `request_key`),
  CONSTRAINT `fk_reservations_1` FOREIGN KEY (`customer_id`) REFERENCES `users` (`id`),
  CONSTRAINT `fk_reservations_2` FOREIGN KEY (`recommendation_candidate_id`) REFERENCES `recommendation_candidates` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='통합 예약';

CREATE TABLE `store_orders` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '점포별 주문 ID',
  `reservation_id` BIGINT NOT NULL COMMENT '통합 예약 ID',
  `store_id` BIGINT NOT NULL COMMENT '점포 ID',
  `product_id` BIGINT NOT NULL COMMENT '상품 ID',
  `quantity` INTEGER NOT NULL COMMENT '수량',
  `unit_price_snapshot` DECIMAL(12,0) NOT NULL COMMENT '단가 사본',
  `product_name_snapshot` VARCHAR(200) NOT NULL COMMENT '상품명 사본',
  `pickup_address_snapshot` VARCHAR(700) NOT NULL COMMENT '수령 주소 사본',
  `requested_pickup_at` DATETIME NOT NULL COMMENT '점포별 수령 희망일시',
  `approval_status` VARCHAR(20) NOT NULL COMMENT '점포 승인 상태',
  `responded_at` DATETIME NULL COMMENT '점포 응답일시',
  `rejection_reason` TEXT NULL COMMENT '거절 사유',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_store_orders_1` (`reservation_id`, `store_id`),
  CONSTRAINT `fk_store_orders_1` FOREIGN KEY (`reservation_id`) REFERENCES `reservations` (`id`),
  CONSTRAINT `fk_store_orders_2` FOREIGN KEY (`store_id`) REFERENCES `stores` (`id`),
  CONSTRAINT `fk_store_orders_3` FOREIGN KEY (`store_id`, `product_id`) REFERENCES `products` (`store_id`, `id`),
  CONSTRAINT ck_store_orders_1 CHECK (quantity >= 1),
  CONSTRAINT ck_store_orders_2 CHECK (unit_price_snapshot >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='점포별 주문';
