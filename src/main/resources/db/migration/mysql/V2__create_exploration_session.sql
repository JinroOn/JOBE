-- ============================================================
-- V2: 탐색형 진단 회차·발급 문항·답변 이력·멱등 저장소·결과
--
-- 근거: 통합과정/업데이트준비완료
--   11_DB_스키마_및_데이터전환_명세.md 2·4·8절
--   D07_MySQL_유일성_및_동시성_계약.md (생성 열 유일키, 잠금 순서, 멱등 저장소)
--   D06_완료결과_계약.md (회차당 결과 1개, 불변)
--
-- 원칙
--   - 사용자당 진행 중(in_progress) 회차 1개: active_user_id 생성 열 UNIQUE. 완료·폐기 회차는 여러 개 보존.
--   - 발급 문항당 최신 답변 최대 1개: current_issued_id 생성 열 UNIQUE.
--     "정확히 1개"는 답변 수정 트랜잭션이 보장한다(이전 current 해제 → 새 revision insert를 한 트랜잭션에서).
--   - answer.is_current(최신 revision)와 issued.effective(현재 분기 사용 여부)는 별개다.
--   - users 테이블 FK는 걸지 않는다. 빈 DB에서는 Flyway가 Hibernate보다 먼저 실행되어 users가 아직 없다.
--     user_id는 토큰의 사용자 ID만 저장하며, FK 연결은 레거시 테이블을 Flyway로 옮길 때 추가한다.
-- ============================================================

-- 회차. 시작 순간의 카탈로그·규칙·의미 계약 버전을 고정한다.
CREATE TABLE exploration_session (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  diagnosis_type VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  -- 실행 모드(D05): DEV(격리 개발·합성 시험) / PILOT(별도 승인 파일럿) / PUBLIC(일반 공개, ACTIVE 묶음만)
  run_mode VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  pilot_approval_reference VARCHAR(200) NULL,
  status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  stage VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  -- 클라이언트 충돌 검증용(expectedRevision). 출제·답변·단계 이동·완료·폐기마다 증가.
  revision BIGINT NOT NULL DEFAULT 0,
  question_catalog_id BIGINT NOT NULL,
  option_catalog_id BIGINT NOT NULL,
  option_variant_catalog_id BIGINT NOT NULL,
  major_mapping_catalog_id BIGINT NOT NULL,
  ruleset_id BIGINT NOT NULL,
  semantic_contract_catalog_id BIGINT NOT NULL,
  started_at DATETIME(6) NOT NULL,
  completed_at DATETIME(6) NULL,
  abandoned_at DATETIME(6) NULL,
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  -- D07: 진행 중일 때만 user_id, 그 외 NULL → UNIQUE로 사용자당 진행 중 1개 보장
  active_user_id BIGINT GENERATED ALWAYS AS
    (CASE WHEN status = 'in_progress' THEN user_id ELSE NULL END) STORED,
  CONSTRAINT uq_exploration_session_active_user UNIQUE (active_user_id),
  CONSTRAINT fk_exploration_session_question_catalog
    FOREIGN KEY (question_catalog_id) REFERENCES exploration_catalog (id),
  CONSTRAINT fk_exploration_session_option_catalog
    FOREIGN KEY (option_catalog_id) REFERENCES exploration_catalog (id),
  CONSTRAINT fk_exploration_session_option_variant_catalog
    FOREIGN KEY (option_variant_catalog_id) REFERENCES exploration_catalog (id),
  CONSTRAINT fk_exploration_session_major_mapping_catalog
    FOREIGN KEY (major_mapping_catalog_id) REFERENCES exploration_catalog (id),
  CONSTRAINT fk_exploration_session_ruleset
    FOREIGN KEY (ruleset_id) REFERENCES exploration_ruleset (id),
  CONSTRAINT fk_exploration_session_semantic_contract_catalog
    FOREIGN KEY (semantic_contract_catalog_id) REFERENCES exploration_catalog (id),
  CONSTRAINT ck_exploration_session_type CHECK (diagnosis_type IN ('EXPLORATION_V1')),
  CONSTRAINT ck_exploration_session_mode CHECK (run_mode IN ('DEV', 'PILOT', 'PUBLIC')),
  CONSTRAINT ck_exploration_session_status CHECK (status IN ('in_progress', 'completed', 'abandoned')),
  CONSTRAINT ck_exploration_session_revision CHECK (revision >= 0),
  INDEX ix_exploration_session_user_started (user_id, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 발급(노출) 문항. 실제 노출한 문구·선택지를 snapshot으로 보존한다.
CREATE TABLE exploration_issued_question (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  session_id BIGINT NOT NULL,
  question_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  question_version VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  comparison_set VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
  variant_version VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
  stage VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  -- 회차 안에서 노출 순서(1부터). 재시도해도 중복되지 않는다.
  ordinal INT NOT NULL,
  -- 현재 분기에서 사용하는 발급인지. 분기 제외 시 false, 답변 이력은 보존.
  effective BOOLEAN NOT NULL DEFAULT TRUE,
  required BOOLEAN NOT NULL DEFAULT TRUE,
  snapshot_json JSON NOT NULL,
  shown_at DATETIME(6) NOT NULL,
  CONSTRAINT fk_exploration_issued_session FOREIGN KEY (session_id) REFERENCES exploration_session (id),
  CONSTRAINT uq_exploration_issued_ordinal UNIQUE (session_id, ordinal),
  CONSTRAINT ck_exploration_issued_ordinal CHECK (ordinal > 0),
  CONSTRAINT ck_exploration_issued_variant_pair CHECK
    ((comparison_set IS NULL AND variant_version IS NULL) OR
     (comparison_set IS NOT NULL AND variant_version IS NOT NULL)),
  INDEX ix_exploration_issued_effective (session_id, effective)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 답변 이력. 수정은 행 갱신이 아니라 revision 추가로 처리한다.
CREATE TABLE exploration_answer_revision (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  issued_id BIGINT NOT NULL,
  answer_revision INT NOT NULL,
  option_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  -- 해당 발급 문항의 최신 답변인지(최대 1개)
  is_current BOOLEAN NOT NULL,
  client_mutation_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  -- 같은 client_mutation_id 재전송 시 본문 비교·성공 응답 재생용(D07 5절)
  mutation_request_hash BINARY(32) NOT NULL,
  mutation_response_json JSON NOT NULL,
  answered_at DATETIME(6) NOT NULL,
  -- D07: 최신 답변일 때만 issued_id, 그 외 NULL → UNIQUE로 최신 답변 최대 1개 보장
  current_issued_id BIGINT GENERATED ALWAYS AS
    (CASE WHEN is_current = 1 THEN issued_id ELSE NULL END) STORED,
  CONSTRAINT fk_exploration_answer_issued FOREIGN KEY (issued_id) REFERENCES exploration_issued_question (id),
  CONSTRAINT uq_exploration_answer_current UNIQUE (current_issued_id),
  CONSTRAINT uq_exploration_answer_revision UNIQUE (issued_id, answer_revision),
  CONSTRAINT uq_exploration_answer_mutation UNIQUE (issued_id, client_mutation_id),
  CONSTRAINT ck_exploration_answer_revision CHECK (answer_revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 멱등 요청 저장소. 예약 row는 원래 작업과 같은 트랜잭션에서 insert하고 성공 응답과 함께 커밋한다.
CREATE TABLE exploration_idempotency (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  operation VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  -- 대상 자원 ID. 회차 시작처럼 대상이 아직 없으면 0.
  resource_id BIGINT NOT NULL,
  idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_hash BINARY(32) NOT NULL,
  response_status SMALLINT NULL,
  response_json JSON NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uq_exploration_idempotency UNIQUE (user_id, operation, resource_id, idempotency_key),
  CONSTRAINT ck_exploration_idempotency_response CHECK
    ((response_status IS NULL AND response_json IS NULL) OR
     (response_status IS NOT NULL AND response_status BETWEEN 200 AND 299 AND response_json IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 완료 결과. 회차당 1개, 완료 후 불변(D06). 후보 없음도 정상 결과로 저장한다.
CREATE TABLE exploration_result (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  session_id BIGINT NOT NULL,
  result_contract_version VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  result_status VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  completion_reason VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  -- 입력 답변·버전 묶음의 SHA-256. 같은 입력이면 같은 결과임을 확인하는 데 쓴다.
  input_hash BINARY(32) NOT NULL,
  result_json JSON NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT fk_exploration_result_session FOREIGN KEY (session_id) REFERENCES exploration_session (id),
  CONSTRAINT uq_exploration_result_session UNIQUE (session_id),
  CONSTRAINT ck_exploration_result_status CHECK (result_status IN ('READY', 'LOW_EVIDENCE', 'NO_SUPPORTED_CANDIDATE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
