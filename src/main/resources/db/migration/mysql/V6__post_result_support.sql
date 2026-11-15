-- Separate personal support state. No update of the immutable exploration_result payload.
CREATE TABLE exploration_post_result (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  result_id BIGINT NOT NULL,
  revision BIGINT NOT NULL DEFAULT 0,
  purpose VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  target_major_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
  support_json JSON NOT NULL,
  context_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  CONSTRAINT uq_exploration_post_result UNIQUE(result_id),
  CONSTRAINT fk_exploration_post_result FOREIGN KEY(result_id) REFERENCES exploration_result(id) ON DELETE CASCADE,
  CONSTRAINT ck_exploration_post_purpose CHECK(purpose IN ('PLAN','ACTIVITY','BOTH')),
  CONSTRAINT ck_exploration_post_revision CHECK(revision>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE exploration_support_issued (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  post_result_id BIGINT NOT NULL,
  question_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  question_version VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  ordinal INT NOT NULL,
  effective BOOLEAN NOT NULL DEFAULT TRUE,
  snapshot_json JSON NOT NULL,
  snapshot_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  shown_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_exploration_support_issued FOREIGN KEY(post_result_id) REFERENCES exploration_post_result(id) ON DELETE CASCADE,
  CONSTRAINT uq_exploration_support_ordinal UNIQUE(post_result_id,ordinal),
  CONSTRAINT uq_exploration_support_identity UNIQUE(post_result_id,question_id,question_version),
  CONSTRAINT ck_exploration_support_budget CHECK(ordinal BETWEEN 1 AND 8)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE exploration_support_answer (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  issued_id BIGINT NOT NULL,
  answer_revision INT NOT NULL,
  option_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  is_current BOOLEAN NOT NULL,
  target_major_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
  client_mutation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  mutation_request_hash BINARY(32) NOT NULL,
  mutation_response_json JSON NOT NULL,
  answered_at DATETIME(6) NOT NULL,
  -- VIRTUAL permits the base issued_id FK to cascade; a STORED generated base forbids it in MySQL.
  current_issued_id BIGINT GENERATED ALWAYS AS (CASE WHEN is_current THEN issued_id ELSE NULL END) VIRTUAL,
  CONSTRAINT fk_exploration_support_answer FOREIGN KEY(issued_id) REFERENCES exploration_support_issued(id) ON DELETE CASCADE,
  CONSTRAINT uq_exploration_support_current UNIQUE(current_issued_id),
  CONSTRAINT uq_exploration_support_revision UNIQUE(issued_id,answer_revision),
  CONSTRAINT uq_exploration_support_mutation UNIQUE(issued_id,client_mutation_id),
  CONSTRAINT ck_exploration_support_answer_revision CHECK(answer_revision>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
