package com.jinroon.jobe.domain.exploration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import com.jinroon.jobe.domain.exploration.service.ExplorationRuntimeCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class ExplorationStageApiTest extends ExplorationApiTestSupport {
    @Autowired ExplorationRuntimeCatalog catalogs;
    @Autowired ExplorationJson json;
    private record Run(long id, JsonNode advance) {}

    @Test void advanceRejectsUnissuedAndUnansweredRequiredQuestionsWithoutLeavingReservation() throws Exception {
        long id = start("start").path("sessionId").asLong();
        assertThat(advance(id, 0, "incomplete", 422).path("code").asText()).isEqualTo("STAGE_INCOMPLETE");
        postJson(id + "/next", owner, "next", revisionBody(0), 200);
        advance(id, 1, "unanswered", 422);
        assertThat(count("exploration_stage_checkpoint", "session_id=?", id)).isZero();
        assertThat(count("exploration_idempotency", "user_id=? AND operation='ADVANCE'", OWNER)).isZero();
    }

    @Test void entryPinsCandidatesAndPlanAndAdvanceReplayDoesNotSkipStages() throws Exception {
        Run run = stageTwo(false); long id = run.id();
        JsonNode before = getJson("/" + id, owner, 200);
        assertThat(before.path("session").path("stage").asInt()).isEqualTo(2);
        assertThat(before.path("fixedCandidates").path("groupCodes").size()).isBetween(1, 3);
        assertThat(before.path("fixedCandidates").toString()).doesNotContain("score", "rankedGroups");
        assertThat(count("exploration_issued_question", "session_id=? AND stage='STAGE_2'", id)).isZero();
        long oldRevision = run.advance().path("session").path("revision").asLong() - 1;
        assertThat(advance(id, oldRevision, "stage-two", 200)).isEqualTo(run.advance());
        JsonNode next = issue(id);
        assertThat(next.path("issuedQuestions").size()).isBetween(1, 24);
        assertThat(next.path("progress").path("stageMax").asInt()).isEqualTo(24);
        assertThat(next.path("progress").path("totalIssued").asInt()).isLessThanOrEqualTo(66);
        assertThat(advance(id, oldRevision, "stage-two", 200)).isEqualTo(run.advance());
        assertThat(advance(id, oldRevision, "stale", 409).path("code").asText()).isEqualTo("STALE_REVISION");
        assertThat(advance(id, currentRevision(id), "unfinished", 422).path("code").asText()).isEqualTo("STAGE_INCOMPLETE");
        assertThat(getJson("/" + id, owner, 200).path("fixedCandidates")).isEqualTo(before.path("fixedCandidates"));
    }

    @Test void allThreeStagesCanIssueAndAcceptHttpAnswersWithoutCreatingFinalResult() throws Exception {
        long id = start("start").path("sessionId").asLong();
        finishStageViaHttp(id);
        JsonNode two = advance(id, currentRevision(id), "stage-two", 200);
        JsonNode fixed = two.path("fixedCandidates");
        finishStageViaHttp(id);
        JsonNode three = advance(id, currentRevision(id), "stage-three", 200);
        assertThat(three.path("session").path("stage").asInt()).isEqualTo(3);
        assertThat(three.path("fixedCandidates")).isEqualTo(fixed);
        finishStageViaHttp(id);
        JsonNode detail = getJson("/" + id, owner, 200);
        assertThat(detail.path("nextAction").asText()).isEqualTo("COMPLETE");
        assertThat(detail.path("session").path("status").asText()).isEqualTo("in_progress");
        assertThat(detail.path("progress").path("stageMax").asInt()).isEqualTo(12);
        assertThat(detail.path("progress").path("issued").asInt()).isLessThanOrEqualTo(11);
        assertThat(detail.path("progress").path("totalIssued").asInt()).isLessThanOrEqualTo(78);
        assertThat(count("exploration_result", "session_id=?", id)).isZero();
        long revision = currentRevision(id);
        assertThat(advance(id, revision, "ready", 200).path("nextAction").asText()).isEqualTo("COMPLETE");
        assertThat(currentRevision(id)).isEqualTo(revision);
    }

    @Test void comparisonQuestionsPersistTheActualCanonicalVariantAndOnlyItsOptions() throws Exception {
        long id = readyOne(false);
        JsonNode detail = getJson("/" + id, owner, 200);
        for (JsonNode row : detail.path("issued")) if (Set.of("STATE-01", "STATE-03", "STATE-06").contains(row.path("question").path("questionId").asText()))
            save(id, row.path("question"), "COMPARE", UUID.randomUUID().toString(), 200);
        Run run = new Run(id, advance(id, currentRevision(id), "stage-two", 200)); JsonNode next = issue(run.id());
        int comparisons = 0;
        for (JsonNode q : next.path("issuedQuestions")) if (!q.path("comparisonSet").isNull()) {
            comparisons++;
            assertThat(q.path("comparisonSet").asText()).startsWith("CMP-").doesNotContain("_");
            String label = catalogs.load(id).payload().path("comparisonQuestionDisplay")
                    .path("labels").path(q.path("comparisonSet").asText()).asText();
            assertThat(label).isNotBlank();
            assertThat(q.path("text").asText()).startsWith(label + " 분야를 비교할 때 ");
            assertThat(q.path("optionVariantVersion").asText()).isNotBlank();
            assertThat(q.toString()).doesNotContain("numeric_value", "meaning_code");
            assertThat(q.path("options").toString()).contains("UNSURE");
            String wrong = "NON_ISSUED_VARIANT_OPTION";
            assertThat(save(run.id(), q, wrong, "wrong-variant", 422).path("code").asText()).isEqualTo("OPTION_NOT_ISSUED");
            save(run.id(), q, q.path("options").get(0).path("code").asText(), UUID.randomUUID().toString(), 200);
        }
        assertThat(comparisons).isBetween(1, 3);
    }

    @Test void comparisonFamiliesUseDifferentPinnedWordingAndKeepLegacyQuestionText() {
        var current = catalogs.selectForDevelopment();
        ObjectNode payload = current.payload().deepCopy();
        payload.remove("comparisonQuestionDisplay");
        var legacy = new ExplorationRuntimeCatalog.Frozen(current.ids(), json.hex(payload), payload, current.branch());
        var clinical = current.branch().key(current.branch().question("CMP-BIO-01"), "CMP-CLINICAL");
        var diagnostics = current.branch().key(current.branch().question("CMP-BIO-01"), "CMP-CLINICAL-TECH");
        var originalClinical = catalogs.questionSnapshot(legacy, clinical);
        var originalDiagnostics = catalogs.questionSnapshot(legacy, diagnostics);
        var newClinical = catalogs.questionSnapshot(current, clinical);
        var newDiagnostics = catalogs.questionSnapshot(current, diagnostics);
        assertThat(originalClinical.path("question")).isEqualTo(originalDiagnostics.path("question"));
        assertThat(newClinical.path("question").path("question_text").asText()).startsWith("임상 돌봄·응급 대응·구강 예방");
        assertThat(newDiagnostics.path("question").path("question_text").asText()).startsWith("의료 영상·검체 검사");
        assertThat(newClinical.path("options")).isEqualTo(originalClinical.path("options"));
        assertThat(newDiagnostics.path("options")).isEqualTo(originalDiagnostics.path("options"));

        var covered = new HashSet<String>();
        current.payload().path("comparisonQuestionDisplay").path("labels").fieldNames().forEachRemaining(covered::add);
        assertThat(covered).containsExactlyInAnyOrderElementsOf(current.branch().variants().keySet());
    }

    @Test void currentStageAnswersKeepEntryPlanAndFixedCandidatesStable() throws Exception {
        Run run = stageTwo(false); JsonNode next = issue(run.id());
        JsonNode candidates = getJson("/" + run.id(), owner, 200).path("fixedCandidates");
        String payload = checkpoint(run.id(), "STAGE_2");
        JsonNode q = next.path("issuedQuestions").get(0);
        JsonNode saved = save(run.id(), q, q.path("options").get(q.path("options").size() - 1).path("code").asText(), "save", 200);
        assertThat(saved.path("rewound").asBoolean()).isFalse();
        assertThat(saved.path("stage").asInt()).isEqualTo(2);
        assertThat(checkpoint(run.id(), "STAGE_2")).isEqualTo(payload);
        assertThat(getJson("/" + run.id(), owner, 200).path("fixedCandidates")).isEqualTo(candidates);
    }

    @Test void priorStageEditInvalidatesLaterCheckpointsAndRestoresMatchingIssuedInstancesOnReentry() throws Exception {
        Run run = stageTwo(false); long id = run.id();
        JsonNode issued = issue(id);
        JsonNode firstTwo = issued.path("issuedQuestions").get(0);
        String code = firstTwo.path("options").get(0).path("code").asText();
        JsonNode saved = save(id, firstTwo, code, "second-answer", 200);
        long iid = firstTwo.path("issuedId").asLong();
        String snapshot = jdbc.queryForObject("SELECT snapshot_json FROM exploration_issued_question WHERE id=?", String.class, iid);
        int countBefore = count("exploration_issued_question", "session_id=?", id);
        long originalCheckpoint = run.advance().path("fixedCandidates").path("checkpointId").asLong();
        JsonNode detail = getJson("/" + id, owner, 200);
        JsonNode old = detail.path("issued").get(0).path("question");
        JsonNode edited = save(id, old, detail.path("issued").get(0).path("answerCode").asText(), "old-edit", 200);
        assertThat(edited.path("rewound").asBoolean()).isTrue();
        assertThat(edited.path("stage").asInt()).isEqualTo(1);
        assertThat(count("exploration_stage_checkpoint", "session_id=? AND valid=true", id)).isZero();
        assertThat(count("exploration_issued_question", "session_id=? AND stage='STAGE_2' AND effective=true", id)).isZero();
        assertThat(count("exploration_answer_revision", "issued_id=? AND is_current=true", iid)).isEqualTo(1);
        // Original success can still replay after rollback to an earlier stage.
        assertThat(postJson(id + "/next", owner, "stage-two-next", revisionBody(run.advance().path("session").path("revision").asLong()), 200)).isEqualTo(issued);
        JsonNode reentered = advance(id, currentRevision(id), "reenter", 200);
        assertThat(reentered.path("fixedCandidates").path("checkpointId").asLong()).isNotEqualTo(originalCheckpoint);
        assertThat(reentered.path("fixedCandidates").path("majorCodes")).isEqualTo(run.advance().path("fixedCandidates").path("majorCodes"));
        JsonNode resumed = getJson("/" + id, owner, 200);
        JsonNode row = issuedRow(resumed, iid);
        assertThat(row.path("effective").asBoolean()).isTrue();
        assertThat(row.path("answerCode").asText()).isEqualTo(code);
        assertThat(jdbc.queryForObject("SELECT snapshot_json FROM exploration_issued_question WHERE id=?", String.class, iid)).isEqualTo(snapshot);
        assertThat(count("exploration_issued_question", "session_id=?", id)).isEqualTo(countBefore);
        assertThat(saved.path("revision").asLong()).isLessThan(currentRevision(id));
    }

    @Test void stageTwoEditFromStageThreeKeepsCandidatesAndInvalidatesOnlyStageThree() throws Exception {
        Run run = stageTwo(false); long id = run.id(); issue(id); seedCurrentStage(id, false);
        advance(id, currentRevision(id), "third", 200); issue(id);
        JsonNode before = getJson("/" + id, owner, 200);
        JsonNode target = null; String answer = null;
        for (JsonNode row : before.path("issued")) if (row.path("stage").asInt() == 2) { target = row.path("question"); answer = row.path("answerCode").asText(); break; }
        assertThat(target).isNotNull();
        JsonNode edited = save(id, target, answer, "edit-two", 200);
        assertThat(edited.path("stage").asInt()).isEqualTo(2);
        assertThat(count("exploration_stage_checkpoint", "session_id=? AND valid=true AND stage='STAGE_2'", id)).isEqualTo(1);
        assertThat(count("exploration_stage_checkpoint", "session_id=? AND valid=true AND stage='STAGE_3'", id)).isZero();
        assertThat(getJson("/" + id, owner, 200).path("fixedCandidates")).isEqualTo(before.path("fixedCandidates"));
        advance(id, currentRevision(id), "third-again", 200);
        assertThat(count("exploration_stage_checkpoint", "session_id=? AND stage='STAGE_3'", id)).isEqualTo(2);
    }

    @Test void noPositiveEvidenceCanAdvanceWithEmptyCandidatesWithoutFabricatingDeepeningQuestions() throws Exception {
        Run run = stageTwo(true);
        assertThat(run.advance().path("fixedCandidates").path("majorCodes").isEmpty()).isTrue();
        JsonNode two = issue(run.id());
        assertThat(two.path("issuedQuestions").isEmpty()).isTrue();
        assertThat(two.path("nextAction").asText()).isEqualTo("ADVANCE");
        JsonNode third = advance(run.id(), currentRevision(run.id()), "third", 200);
        assertThat(third.path("session").path("stage").asInt()).isEqualTo(3);
        JsonNode checkpoint = mapper.readTree(checkpoint(run.id(), "STAGE_3"));
        assertThat(checkpoint.path("candidates").path("majorCodes").isEmpty()).isTrue();
        assertThat(checkpoint.path("plan").path("active").size()).isLessThanOrEqualTo(3);
        assertThat(count("exploration_result", "session_id=?", run.id())).isZero();
    }

    @Test void cautionGateUsesIndividualReviewedEvidenceAndNeverPromotesPendingTags() throws Exception {
        Run run = stageTwo(false); issue(run.id()); seedCurrentStage(run.id(), false);
        advance(run.id(), currentRevision(run.id()), "third", 200);
        JsonNode payload = mapper.readTree(checkpoint(run.id(), "STAGE_3"));
        assertThat(payload.path("eligibleTags").size()).isEqualTo(139);
        for (JsonNode tag : payload.path("eligibleTags")) {
            assertThat(tag.path("contentStatus").asText()).isEqualTo("REVIEWED");
            assertThat(tag.path("implementationReviewStatus").asText()).isEqualTo("APPROVED_FOR_IMPLEMENTATION");
            assertThat(tag.path("domainReviewStatus").asText()).isEqualTo("PENDING");
            assertThat(tag.path("evidence").asText()).isNotBlank();
        }
        JsonNode next = issue(run.id());
        assertThat(next.path("issuedQuestions").size()).isLessThanOrEqualTo(11);
        assertThat(next.toString()).doesNotContain("eligibleTags", "evidence", "domainReviewStatus");
        assertThat(count("exploration_review_history", "1=1")).isZero();
    }

    @Test void checkpointTamperingFailsClosed() throws Exception {
        Run run = stageTwo(false);
        jdbc.update("UPDATE exploration_stage_checkpoint SET payload_json=JSON_SET(payload_json,'$.candidates.groupCodes[0]','INJECTED') WHERE session_id=? AND valid=true", run.id());
        assertThat(getJson("/" + run.id(), owner, 503).path("code").asText()).isEqualTo("CATALOG_INTEGRITY_ERROR");
        postJson(run.id() + "/next", owner, "corrupt", revisionBody(currentRevisionSql(run.id())), 503);
        assertThat(count("exploration_issued_question", "session_id=? AND stage='STAGE_2'", run.id())).isZero();
    }

    @Test void checkpointInsertionFailureRollsBackStageRevisionAndReservationAndSameKeyCanRetry() throws Exception {
        long id = readyOne(false); long revision = currentRevision(id);
        jdbc.execute("CREATE TRIGGER test_checkpoint_failure BEFORE INSERT ON exploration_stage_checkpoint FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic checkpoint failure'");
        try {
            advance(id, revision, "retry", 500);
            assertThat(getJson("/" + id, owner, 200).path("session").path("stage").asInt()).isEqualTo(1);
            assertThat(currentRevision(id)).isEqualTo(revision);
            assertThat(count("exploration_stage_checkpoint", "session_id=?", id)).isZero();
            assertThat(count("exploration_idempotency", "user_id=? AND idempotency_key='retry'", OWNER)).isZero();
        } finally { jdbc.execute("DROP TRIGGER test_checkpoint_failure"); }
        assertThat(advance(id, revision, "retry", 200).path("session").path("stage").asInt()).isEqualTo(2);
    }

    @Test void rewindFailurePreservesCurrentStageCheckpointEffectiveQuestionsAndRevision() throws Exception {
        Run run = stageTwo(false); long id = run.id(); issue(id);
        JsonNode detail = getJson("/" + id, owner, 200);
        JsonNode old = detail.path("issued").get(0).path("question");
        String code = detail.path("issued").get(0).path("answerCode").asText();
        long revision = currentRevision(id);
        int effective = count("exploration_issued_question", "session_id=? AND stage='STAGE_2' AND effective=true", id);
        jdbc.execute("CREATE TRIGGER test_rewind_failure BEFORE UPDATE ON exploration_idempotency FOR EACH ROW "
                + "BEGIN IF NEW.operation='ANSWER' AND NEW.response_status IS NOT NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic rewind failure'; END IF; END");
        try {
            save(id, old, code, "rewind-retry", 500);
            JsonNode after = getJson("/" + id, owner, 200);
            assertThat(after.path("session").path("stage").asInt()).isEqualTo(2);
            assertThat(currentRevision(id)).isEqualTo(revision);
            assertThat(after.path("fixedCandidates")).isEqualTo(detail.path("fixedCandidates"));
            assertThat(count("exploration_stage_checkpoint", "session_id=? AND valid=true", id)).isEqualTo(1);
            assertThat(count("exploration_issued_question", "session_id=? AND stage='STAGE_2' AND effective=true", id)).isEqualTo(effective);
            assertThat(count("exploration_idempotency", "user_id=? AND idempotency_key='rewind-retry'", OWNER)).isZero();
        } finally { jdbc.execute("DROP TRIGGER test_rewind_failure"); }
        assertThat(save(id, old, code, "rewind-retry", 200).path("rewound").asBoolean()).isTrue();
    }

    @Test void rejectedOrEvidenceLessTagsAreExcludedFromTheDevelopmentGate() throws Exception {
        Run run = stageTwo(false); issue(run.id()); seedCurrentStage(run.id(), false);
        var tags = jdbc.queryForList("SELECT t.id,t.evidence FROM exploration_caution_tag t JOIN exploration_major_mapping m ON m.id=t.mapping_id "
                + "JOIN exploration_session s ON s.major_mapping_catalog_id=m.catalog_id WHERE s.id=? AND t.implementation_review_status='APPROVED_FOR_IMPLEMENTATION' ORDER BY t.id LIMIT 2", run.id());
        assertThat(tags).hasSize(2);
        long rejected = ((Number)tags.get(0).get("id")).longValue(), noEvidence = ((Number)tags.get(1).get("id")).longValue();
        try {
            jdbc.update("UPDATE exploration_caution_tag SET domain_review_status='REJECTED' WHERE id=?", rejected);
            jdbc.update("UPDATE exploration_caution_tag SET evidence=NULL WHERE id=?", noEvidence);
            advance(run.id(), currentRevision(run.id()), "third", 200);
            JsonNode payload = mapper.readTree(checkpoint(run.id(), "STAGE_3"));
            assertThat(payload.path("eligibleTags").size()).isEqualTo(137);
            for (JsonNode tag : payload.path("eligibleTags")) assertThat(tag.path("sourceId").asLong()).isNotIn(rejected, noEvidence);
        } finally {
            jdbc.update("UPDATE exploration_caution_tag SET domain_review_status='PENDING' WHERE id=?", rejected);
            jdbc.update("UPDATE exploration_caution_tag SET evidence=? WHERE id=?", tags.get(1).get("evidence"), noEvidence);
        }
    }

    @Test void simultaneousAdvanceWithSameKeyCreatesExactlyOneCheckpoint() throws Exception {
        long id = readyOne(false); long revision = currentRevision(id);
        var replies = parallel(() -> service.advance(OWNER, id, "same-advance", revision));
        assertThat(replies.get(0).body()).isEqualTo(replies.get(1).body());
        assertThat(count("exploration_stage_checkpoint", "session_id=?", id)).isEqualTo(1);
    }

    @Test void simultaneousDifferentAdvanceKeysYieldOneSuccessAndOneStaleConflict() throws Exception {
        long id = readyOne(false); long revision = currentRevision(id);
        var replies = parallel(() -> {
            try { service.advance(OWNER, id, UUID.randomUUID().toString(), revision); return "SUCCESS"; }
            catch (ExplorationApiException e) { return e.getCode(); }
        });
        assertThat(replies).containsExactlyInAnyOrder("SUCCESS", "STALE_REVISION");
        assertThat(count("exploration_stage_checkpoint", "session_id=?", id)).isEqualTo(1);
    }

    @Test void advanceRequiresOwnershipHeaderRevisionAndStrictBody() throws Exception {
        Run run = stageTwo(false);
        postJson(run.id() + "/advance", other, "foreign", revisionBody(0), 404);
        mvc.perform(post(API + "/" + run.id() + "/advance").header("Authorization", owner).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        postJson(run.id() + "/advance", owner, "invalid", "{\"expectedRevision\":1,\"candidates\":[]}", 400);
        postJson(run.id() + "/advance", owner, "invalid", "{\"expectedRevision\":1,\"eligibleTags\":[]}", 400);
        long revision = currentRevision(run.id());
        postJson(run.id() + "/abandon", owner, "abandon", revisionBody(revision), 200);
        assertThat(advance(run.id(), revision + 1, "late", 409).path("code").asText()).isEqualTo("SESSION_ABANDONED");
    }

    @Test void withdrawalDeletesHistoricalAndActivePersonalCheckpoints() throws Exception {
        Run run = stageTwo(false); issue(run.id()); seedCurrentStage(run.id(), false);
        advance(run.id(), currentRevision(run.id()), "third", 200);
        assertThat(count("exploration_stage_checkpoint", "session_id=?", run.id())).isEqualTo(2);
        deletion.delete(OWNER);
        assertThat(count("exploration_stage_checkpoint", "session_id=?", run.id())).isZero();
        getJson("/" + run.id(), owner, 401);
        assertThat(count("exploration_runtime_snapshot", "1=1")).isEqualTo(1);
    }

    private Run stageTwo(boolean unknown) throws Exception {
        long id = readyOne(unknown);
        return new Run(id, advance(id, currentRevision(id), "stage-two", 200));
    }
    private long readyOne(boolean unknown) throws Exception {
        long id = start("start").path("sessionId").asLong();
        for (int n = 0; n < 4; n++) {
            issue(id); seedCurrentStage(id, unknown);
            if (getJson("/" + id, owner, 200).path("nextAction").asText().equals("ADVANCE")) return id;
        }
        throw new AssertionError("stage one not ready");
    }
    private void seedCurrentStage(long id, boolean unknown) throws Exception {
        // Fixture setup only. The full three-stage test submits every answer through HTTP instead.
        JsonNode detail = getJson("/" + id, owner, 200); int stage = detail.path("session").path("stage").asInt(); int changes = 0;
        for (JsonNode row : detail.path("issued")) if (row.path("stage").asInt() == stage && row.path("effective").asBoolean() && row.path("answerCode").isNull()) {
            JsonNode q = row.path("question"); String code = q.path("options").get(0).path("code").asText();
            if (unknown) {
                JsonNode snapshot = mapper.readTree(jdbc.queryForObject("SELECT snapshot_json FROM exploration_issued_question WHERE id=?", String.class, q.path("issuedId").asLong()));
                for (JsonNode option : snapshot.path("options")) if (option.path("is_unknown").asBoolean() || option.path("is_no_experience").asBoolean()) { code = option.path("option_code").asText(); break; }
            }
            jdbc.update("INSERT INTO exploration_answer_revision(issued_id,answer_revision,option_code,is_current,client_mutation_id,mutation_request_hash,mutation_response_json,answered_at) VALUES (?,1,?,true,?,?,JSON_OBJECT(),NOW())",
                    q.path("issuedId").asLong(), code, UUID.randomUUID().toString(), new byte[32]); changes++;
        }
        jdbc.update("UPDATE exploration_session SET revision=revision+? WHERE id=?", changes, id);
    }
    private void finishStageViaHttp(long id) throws Exception {
        for (int n = 0; n < 5; n++) {
            JsonNode next = issue(id);
            for (JsonNode q : next.path("issuedQuestions")) save(id, q, q.path("options").get(0).path("code").asText(), UUID.randomUUID().toString(), 200);
            String action = getJson("/" + id, owner, 200).path("nextAction").asText();
            if (action.equals("ADVANCE") || action.equals("COMPLETE")) return;
        }
        throw new AssertionError("stage not ready");
    }
    private JsonNode issue(long id) throws Exception {
        String key = getJson("/" + id, owner, 200).path("session").path("stage").asInt() == 2 ? "stage-two-next" : UUID.randomUUID().toString();
        return postJson(id + "/next", owner, key, revisionBody(currentRevision(id)), 200);
    }
    private JsonNode advance(long id, long revision, String key, int status) throws Exception { return postJson(id + "/advance", owner, key, revisionBody(revision), status); }
    private String revisionBody(long revision) throws Exception { return mapper.writeValueAsString(Map.of("expectedRevision", revision)); }
    private long currentRevision(long id) throws Exception { return getJson("/" + id, owner, 200).path("session").path("revision").asLong(); }
    private long currentRevisionSql(long id) { return jdbc.queryForObject("SELECT revision FROM exploration_session WHERE id=?", Long.class, id); }
    private JsonNode save(long id, JsonNode q, String code, String mutation, int status) throws Exception {
        return response(put(API + "/" + id + "/answers/" + q.path("issuedId").asLong()).header("Authorization", owner).header("Idempotency-Key", mutation)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("expectedRevision", currentRevision(id), "optionCode", code, "clientMutationId", mutation))), status);
    }
    private String checkpoint(long id, String stage) { return jdbc.queryForObject("SELECT payload_json FROM exploration_stage_checkpoint WHERE session_id=? AND stage=? AND valid=true", String.class, id, stage); }
    private JsonNode issuedRow(JsonNode detail, long iid) {
        for (JsonNode row : detail.path("issued")) if (row.path("question").path("issuedId").asLong() == iid) return row;
        throw new AssertionError("not issued");
    }
}
