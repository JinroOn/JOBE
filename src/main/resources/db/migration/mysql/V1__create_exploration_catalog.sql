-- ============================================================
-- V1: 탐색형 진단(EXPLORATION_V1) 카탈로그 테이블
--
-- 근거: 통합과정/업데이트준비완료
--   11_DB_스키마_및_데이터전환_명세.md 2절(테이블 계약)
--   D05_콘텐츠_상태_및_활성화_계약.md (세 상태 분리, 검토 이력)
--   D07_MySQL_유일성_및_동시성_계약.md (NULL 비교 세트 정규화 생성 열)
--
-- 원칙
--   - 레거시 테이블(users, majors, diagnosis_* 등)은 변경하지 않는다.
--   - 레거시 테이블로의 FK는 걸지 않는다. 빈 DB에서는 Flyway가 Hibernate보다 먼저 실행되어
--     레거시 테이블이 아직 없기 때문이다. major_id 등은 논리 참조로만 둔다.
--   - 코드·버전 식별자는 ascii_bin(대소문자 구분, 바이트 비교), 문구는 utf8mb4.
--   - 세 상태: content_status(수명주기) / implementation_review_status(구현 검토) /
--     domain_review_status(내용 검수). 최상위 승인이 개별 자료 승인을 대신하지 않는다.
-- ============================================================

-- 버전 묶음의 각 카탈로그(문항·선택지·variant·전공 매핑·규칙·의미 계약)
CREATE TABLE exploration_catalog (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  catalog_type VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  version VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  content_status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  implementation_review_status VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  domain_review_status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  -- 원본 파일 내용의 SHA-256(hex). 활성 후 내용 변경 금지 판단에 쓴다.
  content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  -- 원본 파일 구조 버전(예: 전공 JSON schema_version=1.2). 데이터 버전(version)과 별개(D01).
  source_schema_version VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NULL,
  metadata_json JSON NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_exploration_catalog_type_version UNIQUE (catalog_type, version),
  CONSTRAINT ck_exploration_catalog_type CHECK (catalog_type IN
    ('QUESTION_CATALOG', 'OPTION_CATALOG', 'OPTION_VARIANT', 'MAJOR_MAPPING', 'RULESET', 'SEMANTIC_CONTRACT')),
  CONSTRAINT ck_exploration_catalog_content CHECK (content_status IN ('DRAFT', 'REVIEWED', 'ACTIVE', 'ARCHIVED')),
  CONSTRAINT ck_exploration_catalog_impl CHECK (implementation_review_status IN
    ('PENDING', 'APPROVED_FOR_IMPLEMENTATION', 'REJECTED', 'NOT_APPLICABLE')),
  CONSTRAINT ck_exploration_catalog_domain CHECK (domain_review_status IN
    ('PENDING', 'APPROVED', 'REJECTED', 'NOT_APPLICABLE')),
  INDEX ix_exploration_catalog_status_type (content_status, catalog_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 문항 (187개/카탈로그)
CREATE TABLE exploration_question (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  catalog_id BIGINT NOT NULL,
  question_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  question_version VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  stage VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  module_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  metric_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
  content_status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  implementation_review_status VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  domain_review_status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  -- 문구·scoring_role·activation_condition 등 원본 행 전체
  metadata_json JSON NOT NULL,
  CONSTRAINT fk_exploration_question_catalog FOREIGN KEY (catalog_id) REFERENCES exploration_catalog (id),
  CONSTRAINT uq_exploration_question UNIQUE (catalog_id, question_id),
  CONSTRAINT ck_exploration_question_content CHECK (content_status IN ('DRAFT', 'REVIEWED', 'ACTIVE', 'ARCHIVED')),
  CONSTRAINT ck_exploration_question_impl CHECK (implementation_review_status IN
    ('PENDING', 'APPROVED_FOR_IMPLEMENTATION', 'REJECTED', 'NOT_APPLICABLE')),
  CONSTRAINT ck_exploration_question_domain CHECK (domain_review_status IN
    ('PENDING', 'APPROVED', 'REJECTED', 'NOT_APPLICABLE')),
  INDEX ix_exploration_question_stage_module (stage, module_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 선택지 (1,360개/카탈로그). 비교 문항은 comparison_set + variant_version으로 세트별 선택지를 구분한다.
CREATE TABLE exploration_option (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  catalog_id BIGINT NOT NULL,
  question_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  -- D03: 하이픈 canonical 코드(CMP-ART-EDU). option_code는 불투명 식별자로 분해하지 않는다.
  comparison_set VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
  variant_version VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
  option_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  option_order INT NOT NULL,
  metadata_json JSON NOT NULL,
  -- D07: MySQL UNIQUE는 NULL을 서로 다른 값으로 보므로 빈 문자열로 정규화한 생성 열로 유일키를 만든다.
  comparison_set_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin
    GENERATED ALWAYS AS (COALESCE(comparison_set, '')) STORED,
  variant_version_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin
    GENERATED ALWAYS AS (COALESCE(variant_version, '')) STORED,
  CONSTRAINT fk_exploration_option_catalog FOREIGN KEY (catalog_id) REFERENCES exploration_catalog (id),
  CONSTRAINT uq_exploration_option UNIQUE
    (catalog_id, question_id, comparison_set_key, variant_version_key, option_code),
  CONSTRAINT ck_exploration_option_variant_pair CHECK
    ((comparison_set IS NULL AND variant_version IS NULL) OR
     (comparison_set IS NOT NULL AND variant_version IS NOT NULL)),
  CONSTRAINT ck_exploration_option_set_nonempty CHECK (comparison_set IS NULL OR
    (OCTET_LENGTH(comparison_set) > 0 AND OCTET_LENGTH(comparison_set) = OCTET_LENGTH(TRIM(comparison_set)))),
  CONSTRAINT ck_exploration_option_variant_nonempty CHECK (variant_version IS NULL OR
    (OCTET_LENGTH(variant_version) > 0 AND OCTET_LENGTH(variant_version) = OCTET_LENGTH(TRIM(variant_version)))),
  CONSTRAINT ck_exploration_option_order CHECK (option_order > 0),
  INDEX ix_exploration_option_question (catalog_id, question_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 계산·분기 규칙 (현재 RULESET_V1_4). 활성 후 내용 변경 금지.
CREATE TABLE exploration_ruleset (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  version VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  content_status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  implementation_review_status VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  domain_review_status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  rules_json JSON NOT NULL,
  content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_exploration_ruleset_version UNIQUE (version),
  CONSTRAINT ck_exploration_ruleset_content CHECK (content_status IN ('DRAFT', 'REVIEWED', 'ACTIVE', 'ARCHIVED')),
  CONSTRAINT ck_exploration_ruleset_impl CHECK (implementation_review_status IN
    ('PENDING', 'APPROVED_FOR_IMPLEMENTATION', 'REJECTED', 'NOT_APPLICABLE')),
  CONSTRAINT ck_exploration_ruleset_domain CHECK (domain_review_status IN
    ('PENDING', 'APPROVED', 'REJECTED', 'NOT_APPLICABLE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 전공 94개 매핑. major_id는 운영 majors.id 논리 참조이며,
-- MAJOR_ROW_* + 이름 + source_file 3중 대조 전까지 NULL일 수 있다(11번 명세 5절).
CREATE TABLE exploration_major_mapping (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  catalog_id BIGINT NOT NULL,
  major_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  major_id BIGINT NULL,
  mapping_json JSON NOT NULL,
  content_status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  implementation_review_status VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  domain_review_status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  CONSTRAINT fk_exploration_major_mapping_catalog FOREIGN KEY (catalog_id) REFERENCES exploration_catalog (id),
  CONSTRAINT uq_exploration_major_mapping_code UNIQUE (catalog_id, major_code),
  CONSTRAINT uq_exploration_major_mapping_major UNIQUE (catalog_id, major_id),
  CONSTRAINT ck_exploration_major_mapping_content CHECK (content_status IN ('DRAFT', 'REVIEWED', 'ACTIVE', 'ARCHIVED')),
  CONSTRAINT ck_exploration_major_mapping_impl CHECK (implementation_review_status IN
    ('PENDING', 'APPROVED_FOR_IMPLEMENTATION', 'REJECTED', 'NOT_APPLICABLE')),
  CONSTRAINT ck_exploration_major_mapping_domain CHECK (domain_review_status IN
    ('PENDING', 'APPROVED', 'REJECTED', 'NOT_APPLICABLE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 전공별 주의 태그. 내부 승인 139 / 외부 검토 대기 48. 미승인 태그는 분기·결과에 쓰지 않는다.
CREATE TABLE exploration_caution_tag (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  mapping_id BIGINT NOT NULL,
  tag_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  proposal_status VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  -- 원본 검토표의 status. 실행 승인 플래그로 쓰지 않는다(D05).
  legacy_review_status VARCHAR(60) CHARACTER SET ascii COLLATE ascii_bin NULL,
  content_status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  implementation_review_status VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  domain_review_status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  evidence TEXT NULL,
  CONSTRAINT fk_exploration_caution_tag_mapping FOREIGN KEY (mapping_id) REFERENCES exploration_major_mapping (id),
  CONSTRAINT uq_exploration_caution_tag UNIQUE (mapping_id, tag_code),
  CONSTRAINT ck_exploration_caution_tag_content CHECK (content_status IN ('DRAFT', 'REVIEWED', 'ACTIVE', 'ARCHIVED')),
  CONSTRAINT ck_exploration_caution_tag_impl CHECK (implementation_review_status IN
    ('PENDING', 'APPROVED_FOR_IMPLEMENTATION', 'REJECTED', 'NOT_APPLICABLE')),
  CONSTRAINT ck_exploration_caution_tag_domain CHECK (domain_review_status IN
    ('PENDING', 'APPROVED', 'REJECTED', 'NOT_APPLICABLE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 검토 이력 (append-only). 기록 없이 승인 완료를 자동 생성하지 않는다(D05).
CREATE TABLE exploration_review_history (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  target_type VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  target_id BIGINT NOT NULL,
  target_version VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  target_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  review_kind VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  decision VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reviewer_id BIGINT NOT NULL,
  reviewed_at DATETIME(6) NOT NULL,
  evidence_reference TEXT NULL,
  scope TEXT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT ck_exploration_review_kind CHECK (review_kind IN ('IMPLEMENTATION', 'DOMAIN', 'PILOT', 'RELEASE')),
  INDEX ix_exploration_review_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
