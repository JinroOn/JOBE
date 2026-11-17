ALTER TABLE exploration_generation_task
 DROP CHECK ck_generation_state,
 ADD CONSTRAINT ck_generation_state CHECK(state IN ('QUEUED','RUNNING','CANCELLED','MOCK_STORED','FAILED')),
 ADD COLUMN next_attempt_at DATETIME(6) NULL,
 ADD COLUMN failure_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD INDEX ix_generation_due(state,next_attempt_at,lease_until,id);
