-- 3.2.2 카탈로그 승인·활성화 연계: ACTIVE 전환·보관(철회) 이력. 추가만 하며 수정·삭제하지 않는다.
-- 검토 이력(exploration_review_history)과 별개로, 누가 어떤 승인 근거로 어느 범위에 활성화했는지 남긴다.
CREATE TABLE exploration_catalog_activation (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  target_type VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  target_id BIGINT NOT NULL,
  target_version VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  target_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  action VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  -- ACTIVATE에만 쓴다. 활성화 시 확인한 사용 범위(DEV/PILOT/PUBLIC).
  scope VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NULL,
  actor_id BIGINT NOT NULL,
  -- ACTIVATE로 자리를 넘겨받은 이전 ACTIVE 대상, 또는 교체로 보관된 대상을 넘겨받은 새 대상.
  related_target_id BIGINT NULL,
  -- 활성화 판단에 쓴 검토 이력 id 목록(JSON 배열). 보관에는 비어 있다.
  review_ids JSON NULL,
  reason TEXT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  CONSTRAINT ck_catalog_activation_type CHECK (target_type IN ('CATALOG', 'RULESET', 'ACTIVITY_CATALOG')),
  CONSTRAINT ck_catalog_activation_action CHECK (action IN ('ACTIVATE', 'ARCHIVE')),
  CONSTRAINT ck_catalog_activation_scope CHECK (scope IS NULL OR scope IN ('DEV', 'PILOT', 'PUBLIC')),
  INDEX ix_catalog_activation_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
