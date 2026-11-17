ALTER TABLE exploration_generation_task
 DROP CHECK ck_generation_state,
 DROP CHECK ck_generation_result,
 ADD COLUMN execution_mode VARCHAR(10) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'MOCK',
 ADD CONSTRAINT ck_generation_mode CHECK(execution_mode IN ('MOCK','MODEL')),
 ADD CONSTRAINT ck_generation_state CHECK(state IN ('QUEUED','RUNNING','CANCELLED','MOCK_STORED','MODEL_STORED','FAILED')),
 ADD CONSTRAINT ck_generation_result CHECK((state IN ('MOCK_STORED','MODEL_STORED') AND result_json IS NOT NULL AND result_hash IS NOT NULL) OR (state NOT IN ('MOCK_STORED','MODEL_STORED') AND result_json IS NULL AND result_hash IS NULL)),
 ADD CONSTRAINT ck_generation_stored_mode CHECK((state<>'MOCK_STORED' OR execution_mode='MOCK') AND (state<>'MODEL_STORED' OR execution_mode='MODEL'));

DELIMITER //
CREATE TRIGGER immutable_generation_execution_mode BEFORE UPDATE ON exploration_generation_task FOR EACH ROW
BEGIN
 IF NEW.execution_mode<>OLD.execution_mode THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Immutable generation execution mode';
 END IF;
END//
DELIMITER ;
