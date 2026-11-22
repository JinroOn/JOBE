-- Personal immutable inputs for future consultation/plan adapters, not generated sessions or plans.
CREATE TABLE exploration_result_context (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 result_type VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 result_id BIGINT NOT NULL,
 context_type VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 target_major_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
 support_revision BIGINT NOT NULL,
 contract_version VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 result_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 input_hash BINARY(32) NOT NULL,
 snapshot_json JSON NOT NULL,
 created_at DATETIME(6) NOT NULL,
 CONSTRAINT fk_result_context_result FOREIGN KEY(result_id) REFERENCES exploration_result(id) ON DELETE CASCADE,
 CONSTRAINT uq_result_context_input UNIQUE(result_id,context_type,input_hash),
 CONSTRAINT ck_result_context_type CHECK(result_type='EXPLORATION_V1'),
 CONSTRAINT ck_result_context_purpose CHECK(context_type IN ('CONSULTATION','PLAN')),
 CONSTRAINT ck_result_context_revision CHECK(support_revision>=0),
 CONSTRAINT ck_result_context_plan_target CHECK(context_type<>'PLAN' OR target_major_code IS NOT NULL),
 INDEX ix_result_context_page(result_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
