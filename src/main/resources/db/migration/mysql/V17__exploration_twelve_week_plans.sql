CREATE TABLE exploration_plan (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 result_id BIGINT NOT NULL,
 context_id BIGINT NOT NULL,
 context_type VARCHAR(30) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PLAN',
 major_code VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 major_name VARCHAR(100) NOT NULL,
 support_revision BIGINT NOT NULL,
 mutation_hash BINARY(32) NOT NULL,
 client_request_json JSON NOT NULL,
 client_request_hash BINARY(32) NOT NULL,
 generation_mutation VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_json JSON NOT NULL,
 request_hash BINARY(32) NOT NULL,
 publication_json JSON NULL,
 publication_hash BINARY(32) NULL,
 proof_json JSON NULL,
 proof_hash BINARY(32) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT (UTC_TIMESTAMP(6)),
 CONSTRAINT fk_plan_context FOREIGN KEY(context_id,result_id,context_type)
  REFERENCES exploration_result_context(id,result_id,context_type) ON DELETE CASCADE,
 CONSTRAINT uq_plan_mutation UNIQUE(result_id,mutation_hash),
 CONSTRAINT uq_plan_generation UNIQUE(generation_mutation),
 CONSTRAINT ck_plan_context CHECK(context_type='PLAN' AND support_revision>=0),
 CONSTRAINT ck_plan_wire CHECK(JSON_CONTAINS_PATH(request_json,'all','$.contractVersion','$.purpose','$.input.planSpec')=1
  AND JSON_UNQUOTE(JSON_EXTRACT(request_json,'$.contractVersion'))='EXPLORATION_AI_CONTRACT_V1_2'
  AND JSON_UNQUOTE(JSON_EXTRACT(request_json,'$.purpose'))='PLAN'),
 CONSTRAINT ck_plan_publication CHECK(
  (publication_json IS NULL AND publication_hash IS NULL AND proof_json IS NULL AND proof_hash IS NULL)
  OR (publication_json IS NOT NULL AND publication_hash IS NOT NULL AND proof_json IS NOT NULL AND proof_hash IS NOT NULL)),
 INDEX ix_plan_result_page(result_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DELIMITER //
CREATE TRIGGER immutable_exploration_plan BEFORE UPDATE ON exploration_plan
FOR EACH ROW BEGIN
 IF NEW.result_id<>OLD.result_id OR NEW.context_id<>OLD.context_id OR NEW.context_type<>OLD.context_type
  OR NEW.major_code<>OLD.major_code OR BINARY NEW.major_name<>BINARY OLD.major_name OR NEW.support_revision<>OLD.support_revision
  OR NEW.mutation_hash<>OLD.mutation_hash OR NEW.client_request_hash<>OLD.client_request_hash
  OR NOT (NEW.client_request_json <=> OLD.client_request_json) OR NEW.generation_mutation<>OLD.generation_mutation
  OR NEW.request_hash<>OLD.request_hash OR NOT (NEW.request_json <=> OLD.request_json)
  OR (OLD.publication_json IS NOT NULL AND (
   NOT (NEW.publication_json <=> OLD.publication_json) OR NOT (NEW.publication_hash <=> OLD.publication_hash)
   OR NOT (NEW.proof_json <=> OLD.proof_json) OR NOT (NEW.proof_hash <=> OLD.proof_hash))) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Plan source, request and publication are immutable';
 END IF;
END//
DELIMITER ;
