-- Personal stage-entry decisions. Old decisions remain available until account deletion.
CREATE TABLE exploration_stage_checkpoint (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  session_id BIGINT NOT NULL,
  stage VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  entered_revision BIGINT NOT NULL,
  valid BOOLEAN NOT NULL DEFAULT TRUE,
  payload_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  payload_json JSON NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  active_stage VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin
    GENERATED ALWAYS AS (CASE WHEN valid THEN stage ELSE NULL END) STORED,
  CONSTRAINT fk_exploration_checkpoint_session FOREIGN KEY(session_id)
    REFERENCES exploration_session(id) ON DELETE CASCADE,
  CONSTRAINT uq_exploration_checkpoint_active UNIQUE(session_id,active_stage),
  CONSTRAINT uq_exploration_checkpoint_revision UNIQUE(session_id,entered_revision),
  CONSTRAINT ck_exploration_checkpoint_stage CHECK(stage IN ('STAGE_2','STAGE_3')),
  CONSTRAINT ck_exploration_checkpoint_revision CHECK(entered_revision>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
