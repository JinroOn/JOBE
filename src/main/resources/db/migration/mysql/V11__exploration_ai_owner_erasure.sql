CREATE TABLE exploration_ai_owner (
 user_id BIGINT NOT NULL PRIMARY KEY,
 owner_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE
) ENGINE=InnoDB;

-- Deliberately has no user FK: survives account deletion, contains no user ID or input text.
CREATE TABLE exploration_ai_erasure_outbox (
 owner_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 attempt_count INT NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX ix_ai_erasure_due(next_attempt_at,created_at)
) ENGINE=InnoDB;
