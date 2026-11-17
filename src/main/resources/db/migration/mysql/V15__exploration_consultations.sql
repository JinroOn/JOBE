-- Exploration-only consumers. Legacy consultation_sessions/logs are intentionally separate.
ALTER TABLE exploration_result_context ADD CONSTRAINT uq_context_id_result_type UNIQUE(id,result_id,context_type);

CREATE TABLE exploration_consultation (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 result_id BIGINT NOT NULL,
 context_id BIGINT NOT NULL,
 context_type VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'CONSULTATION',
 revision BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
 CONSTRAINT uq_consultation_result UNIQUE(result_id),
 CONSTRAINT fk_consultation_context FOREIGN KEY(context_id,result_id,context_type)
  REFERENCES exploration_result_context(id,result_id,context_type) ON DELETE CASCADE,
 CONSTRAINT ck_consultation_context_type CHECK(context_type='CONSULTATION'),
 CONSTRAINT ck_consultation_revision CHECK(revision>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- A future message consumer reserves a turn before invoking a provider outside the transaction.
-- Completed reply and its sources will be committed with both message rows by that consumer.
CREATE TABLE exploration_consultation_turn (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 consultation_id BIGINT NOT NULL,
 turn_no BIGINT NOT NULL,
 mutation_hash BINARY(32) NOT NULL,
 generation_mutation VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_json JSON NOT NULL,
 request_hash BINARY(32) NOT NULL,
 state VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
 created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
 CONSTRAINT fk_consultation_turn FOREIGN KEY(consultation_id) REFERENCES exploration_consultation(id) ON DELETE CASCADE,
 CONSTRAINT uq_consultation_mutation UNIQUE(consultation_id,mutation_hash),
 CONSTRAINT uq_consultation_turn UNIQUE(consultation_id,turn_no),
 CONSTRAINT uq_consultation_generation UNIQUE(generation_mutation),
 CONSTRAINT ck_consultation_turn_no CHECK(turn_no>0),
 CONSTRAINT ck_consultation_turn_state CHECK(state IN ('PENDING','COMPLETED','FAILED')),
 CONSTRAINT ck_consultation_request CHECK(
  JSON_CONTAINS_PATH(request_json,'all','$.contractVersion','$.purpose','$.input.conversation')=1
  AND JSON_UNQUOTE(JSON_EXTRACT(request_json,'$.contractVersion'))='EXPLORATION_AI_CONTRACT_V1_1'
  AND JSON_UNQUOTE(JSON_EXTRACT(request_json,'$.purpose'))='CONSULTATION'
  AND JSON_TYPE(JSON_EXTRACT(request_json,'$.input.conversation'))='OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE exploration_consultation_message (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 turn_id BIGINT NOT NULL,
 role VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 content TEXT NOT NULL,
 sources_json JSON NOT NULL,
 evidence_status VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NULL,
 message_hash BINARY(32) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
 CONSTRAINT fk_consultation_message FOREIGN KEY(turn_id) REFERENCES exploration_consultation_turn(id) ON DELETE CASCADE,
 CONSTRAINT uq_consultation_message_role UNIQUE(turn_id,role),
 CONSTRAINT ck_consultation_message_shape CHECK(
  (role='user' AND CHAR_LENGTH(content) BETWEEN 1 AND 2000 AND JSON_TYPE(sources_json)='ARRAY'
   AND JSON_LENGTH(sources_json)=0 AND evidence_status IS NULL)
  OR (role='assistant' AND CHAR_LENGTH(content) BETWEEN 1 AND 4000 AND JSON_TYPE(sources_json)='ARRAY'
   AND evidence_status IS NOT NULL AND evidence_status IN ('GROUNDED','INSUFFICIENT')
   AND ((evidence_status='GROUNDED' AND JSON_LENGTH(sources_json)>0)
     OR (evidence_status='INSUFFICIENT' AND JSON_LENGTH(sources_json)=0))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DELIMITER //
CREATE TRIGGER immutable_exploration_consultation BEFORE UPDATE ON exploration_consultation
FOR EACH ROW BEGIN
 IF NEW.result_id<>OLD.result_id OR NEW.context_id<>OLD.context_id OR NEW.context_type<>OLD.context_type THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Consultation source is immutable';
 END IF;
END//
CREATE TRIGGER immutable_exploration_consultation_turn BEFORE UPDATE ON exploration_consultation_turn
FOR EACH ROW BEGIN
 IF NEW.consultation_id<>OLD.consultation_id OR NEW.turn_no<>OLD.turn_no
  OR NEW.mutation_hash<>OLD.mutation_hash OR NEW.generation_mutation<>OLD.generation_mutation
  OR NOT (NEW.request_json <=> OLD.request_json) OR NEW.request_hash<>OLD.request_hash
  OR (OLD.state<>'PENDING' AND NEW.state<>OLD.state) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Consultation turn binding is immutable';
 END IF;
END//
DELIMITER ;
