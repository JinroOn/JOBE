ALTER TABLE exploration_result_context
 DROP CHECK ck_result_context_purpose,
 ADD CONSTRAINT ck_result_context_purpose CHECK(context_type IN ('CONSULTATION','PLAN','EXPLANATION')),
 ADD CONSTRAINT ck_result_context_explanation_target CHECK(
  context_type <> 'EXPLANATION' OR (target_major_code IS NOT NULL AND support_revision = 0)
 );
