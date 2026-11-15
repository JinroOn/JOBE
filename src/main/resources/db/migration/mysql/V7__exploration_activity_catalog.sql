-- Shared content only. Importing a draft never creates approvals or personal recommendations.
CREATE TABLE exploration_activity_catalog (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 version VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
 major_mapping_version VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 payload_json JSON NOT NULL,
 content_status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 implementation_review_status VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 domain_review_status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 CONSTRAINT ck_activity_catalog_content CHECK(content_status IN ('DRAFT','REVIEWED','ACTIVE','ARCHIVED')),
 CONSTRAINT ck_activity_catalog_impl CHECK(implementation_review_status IN ('PENDING','APPROVED_FOR_IMPLEMENTATION','REJECTED')),
 CONSTRAINT ck_activity_catalog_domain CHECK(domain_review_status IN ('PENDING','APPROVED','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE exploration_activity (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 catalog_id BIGINT NOT NULL,
 activity_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 version VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 major_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
 content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 payload_json JSON NOT NULL,
 content_status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 implementation_review_status VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 domain_review_status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 CONSTRAINT fk_activity_catalog FOREIGN KEY(catalog_id) REFERENCES exploration_activity_catalog(id),
 CONSTRAINT uq_activity_identity UNIQUE(catalog_id,activity_code),
 CONSTRAINT ck_activity_content CHECK(content_status IN ('DRAFT','REVIEWED','ACTIVE','ARCHIVED')),
 CONSTRAINT ck_activity_impl CHECK(implementation_review_status IN ('PENDING','APPROVED_FOR_IMPLEMENTATION','REJECTED')),
 CONSTRAINT ck_activity_domain CHECK(domain_review_status IN ('PENDING','APPROVED','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
