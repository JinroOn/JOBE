ALTER TABLE exploration_consultation_turn
 ADD COLUMN base_revision BIGINT NOT NULL DEFAULT 0,
 ADD COLUMN reply_json JSON NULL,
 ADD COLUMN reply_hash BINARY(32) NULL,
 ADD COLUMN proof_json JSON NULL,
 ADD COLUMN proof_hash BINARY(32) NULL,
 ADD COLUMN completed_at DATETIME(6) NULL,
 ADD COLUMN failure_code VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN pending_slot INT GENERATED ALWAYS AS (IF(state='PENDING',1,NULL)) STORED,
 ADD CONSTRAINT uq_consultation_pending UNIQUE(consultation_id,pending_slot),
 ADD CONSTRAINT ck_consultation_base_revision CHECK(base_revision>=0),
 ADD CONSTRAINT ck_consultation_publication CHECK(
  (state='COMPLETED' AND reply_json IS NOT NULL AND reply_hash IS NOT NULL
   AND proof_json IS NOT NULL AND proof_hash IS NOT NULL AND completed_at IS NOT NULL AND failure_code IS NULL)
  OR (state<>'COMPLETED' AND reply_json IS NULL AND reply_hash IS NULL
   AND proof_json IS NULL AND proof_hash IS NULL AND completed_at IS NULL));

DROP TRIGGER immutable_exploration_consultation_turn;
DELIMITER //
CREATE TRIGGER immutable_exploration_consultation_turn BEFORE UPDATE ON exploration_consultation_turn
FOR EACH ROW BEGIN
 IF NEW.consultation_id<>OLD.consultation_id OR NEW.turn_no<>OLD.turn_no
  OR NEW.mutation_hash<>OLD.mutation_hash OR NEW.generation_mutation<>OLD.generation_mutation
  OR NEW.base_revision<>OLD.base_revision
  OR NOT (NEW.request_json <=> OLD.request_json) OR NEW.request_hash<>OLD.request_hash
  OR (OLD.state<>'PENDING' AND (NEW.state<>OLD.state
   OR NOT (NEW.reply_json <=> OLD.reply_json) OR NOT (NEW.reply_hash <=> OLD.reply_hash)
   OR NOT (NEW.proof_json <=> OLD.proof_json) OR NOT (NEW.proof_hash <=> OLD.proof_hash)
   OR NOT (NEW.completed_at <=> OLD.completed_at) OR NOT (NEW.failure_code <=> OLD.failure_code))) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Consultation turn binding and publication are immutable';
 END IF;
END//
DELIMITER ;
