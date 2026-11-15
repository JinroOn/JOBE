-- Runtime snapshots contain shared catalog content, never personal answers.
CREATE TABLE exploration_runtime_snapshot (
  content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
  payload_json JSON NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE exploration_session_pin (
  session_id BIGINT NOT NULL PRIMARY KEY,
  snapshot_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  CONSTRAINT fk_exploration_pin_session FOREIGN KEY (session_id)
    REFERENCES exploration_session(id) ON DELETE CASCADE,
  CONSTRAINT fk_exploration_pin_snapshot FOREIGN KEY (snapshot_hash)
    REFERENCES exploration_runtime_snapshot(content_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- D07: logical identity, including the nullable ordinary-question variant.
ALTER TABLE exploration_issued_question
  ADD COLUMN comparison_set_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin
    GENERATED ALWAYS AS (COALESCE(comparison_set, '')) STORED,
  ADD COLUMN variant_version_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin
    GENERATED ALWAYS AS (COALESCE(variant_version, '')) STORED,
  ADD CONSTRAINT uq_exploration_issued_identity UNIQUE
    (session_id, question_id, question_version, comparison_set_key, variant_version_key),
  ADD CONSTRAINT ck_exploration_issued_set_nonempty CHECK
    (comparison_set IS NULL OR (OCTET_LENGTH(comparison_set)>0 AND OCTET_LENGTH(comparison_set)=OCTET_LENGTH(TRIM(comparison_set)))),
  ADD CONSTRAINT ck_exploration_issued_variant_nonempty CHECK
    (variant_version IS NULL OR (OCTET_LENGTH(variant_version)>0 AND OCTET_LENGTH(variant_version)=OCTET_LENGTH(TRIM(variant_version))));
