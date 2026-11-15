-- Integrity seal of canonical stored results. Personal seals are purged with the result.
CREATE TABLE exploration_result_seal (
  result_id BIGINT NOT NULL PRIMARY KEY,
  content_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_exploration_result_seal FOREIGN KEY(result_id)
    REFERENCES exploration_result(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
