package com.jinroon.jobe.domain.exploration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class ExplorationAnswerApiTest extends ExplorationApiTestSupport {
    private record Run(long id, JsonNode questions) {}
    private Run issued() throws Exception {
        long id = start("start").path("sessionId").asLong();
        return new Run(id, postJson(id + "/next", owner, "next", "{\"expectedRevision\":0}", 200).path("issuedQuestions"));
    }

    @Test void savesAndEditsWithExactlyOneCurrentRevisionAndResumesFromSnapshot() throws Exception {
        Run run = issued(); JsonNode q = run.questions().get(0);
        JsonNode first = answer(run.id(), q, 1, 0);
        JsonNode edited = answer(run.id(), q, 2, 1);
        assertThat(first.path("answerRevision").asInt()).isEqualTo(1);
        assertThat(edited.path("answerRevision").asInt()).isEqualTo(2);
        assertThat(edited.path("revision").asLong()).isEqualTo(3);
        long iid = q.path("issuedId").asLong();
        assertThat(count("exploration_answer_revision", "issued_id=?", iid)).isEqualTo(2);
        assertThat(count("exploration_answer_revision", "issued_id=? AND is_current=true", iid)).isEqualTo(1);
        JsonNode row = row(getJson("/" + run.id(), owner, 200), iid);
        assertThat(row.path("answerRevision").asInt()).isEqualTo(2);
        assertThat(row.path("answerCode").asText()).isEqualTo(q.path("options").get(1).path("code").asText());
        assertThat(row.path("question")).isEqualTo(q);
        assertThat(count("exploration_issued_question", "session_id=?", run.id())).isEqualTo(33);
    }

    @Test void headerRetryReplaysOriginalSuccessAfterLaterEdit() throws Exception {
        Run run = issued(); JsonNode q = run.questions().get(0);
        String code = q.path("options").get(0).path("code").asText();
        JsonNode first = put(run.id(), q.path("issuedId").asLong(), owner, "original", body(1, code, "mutation"), 200);
        answer(run.id(), q, 2, 1);
        assertThat(put(run.id(), q.path("issuedId").asLong(), owner, "original", body(1, code, "mutation"), 200)).isEqualTo(first);
        assertThat(count("exploration_answer_revision", "issued_id=?", q.path("issuedId").asLong())).isEqualTo(2);
    }

    @Test void mutationRetryWithNewHeaderReplaysAndChecksBodyConflictBeforeStaleRevision() throws Exception {
        Run run = issued(); JsonNode q = run.questions().get(0); long iid = q.path("issuedId").asLong();
        String code = q.path("options").get(0).path("code").asText();
        JsonNode first = put(run.id(), iid, owner, "first", body(1, code, "mutation"), 200);
        assertThat(put(run.id(), iid, owner, "alias", body(1, code, "mutation"), 200)).isEqualTo(first);
        assertThat(put(run.id(), iid, owner, "conflict", body(2, code, "mutation"), 409).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(put(run.id(), iid, owner, "first", body(2, code, "new-mutation"), 409).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(count("exploration_answer_revision", "issued_id=?", iid)).isEqualTo(1);
        assertThat(count("exploration_idempotency", "operation='ANSWER' AND user_id=?", OWNER)).isEqualTo(2);
    }

    @Test void staleMutationDoesNotChangeCurrentOrLeaveReservation() throws Exception {
        Run run = issued(); JsonNode q = run.questions().get(0);
        answer(run.id(), q, 1, 0);
        assertThat(put(run.id(), q.path("issuedId").asLong(), owner, "stale", body(1, q.path("options").get(1).path("code").asText(), "late"), 409)
                .path("code").asText()).isEqualTo("STALE_REVISION");
        assertThat(count("exploration_answer_revision", "issued_id=?", q.path("issuedId").asLong())).isEqualTo(1);
        assertThat(count("exploration_idempotency", "user_id=? AND idempotency_key='stale'", OWNER)).isZero();
    }

    @Test void foreignSessionForeignIssuedAndUnissuedIdsReturn404() throws Exception {
        Run run = issued(); long iid = run.questions().get(0).path("issuedId").asLong();
        put(run.id(), iid, other, "foreign", body(1, "BROAD", "m"), 404);
        long otherSession = postJson("", other, "start", START, 201).path("sessionId").asLong();
        put(otherSession, iid, other, "foreign-instance", body(0, "BROAD", "m"), 404);
        put(run.id(), Long.MAX_VALUE, owner, "not-issued", body(1, "BROAD", "m"), 404);
        assertThat(count("exploration_answer_revision", "issued_id=?", iid)).isZero();
    }

    @Test void unknownOrCaseChangedOptionsReturn422AndDoNotStoreAnswers() throws Exception {
        Run run = issued(); long iid = run.questions().get(0).path("issuedId").asLong();
        for (String code : List.of("NOT_ISSUED", "broad", " BROAD"))
            assertThat(put(run.id(), iid, owner, UUID.randomUUID().toString(), body(1, code, "m"), 422).path("code").asText()).isEqualTo("OPTION_NOT_ISSUED");
        assertThat(count("exploration_answer_revision", "issued_id=?", iid)).isZero();
    }

    @Test void exclusionPreservesCurrentAnswerAndRestorationKeepsIssuedIdentityAndBudget() throws Exception {
        Run run = issued(); JsonNode one = question(run, "STATE-01"), three = question(run, "STATE-03"), six = question(run, "STATE-06");
        answer(run.id(), one, 1, 0); answer(run.id(), three, 2, 1); answer(run.id(), six, 3, 0);
        JsonNode extra = postJson(run.id() + "/next", owner, "extra", "{\"expectedRevision\":4}", 200).path("issuedQuestions");
        JsonNode followup = null;
        for (JsonNode q : extra) if (q.path("questionId").asText().equals("STATE-02")) followup = q;
        assertThat(followup).isNotNull(); long iid = followup.path("issuedId").asLong();
        String snapshot = jdbc.queryForObject("SELECT snapshot_json FROM exploration_issued_question WHERE id=?", String.class, iid);
        int ordinal = jdbc.queryForObject("SELECT ordinal FROM exploration_issued_question WHERE id=?", Integer.class, iid);
        String code = followup.path("options").get(0).path("code").asText();
        JsonNode saved = put(run.id(), iid, owner, "followup", body(5, code, "followup-m"), 200);
        JsonNode excluded = answer(run.id(), three, 6, 0);
        assertThat(excluded.path("branchChanges").path("excludedIssuedIds").size()).isEqualTo(3);
        JsonNode detail = getJson("/" + run.id(), owner, 200);
        assertThat(row(detail, iid).path("effective").asBoolean()).isFalse();
        assertThat(row(detail, iid).path("answerCode").asText()).isEqualTo(code);
        assertThat(count("exploration_answer_revision", "issued_id=? AND is_current=true", iid)).isEqualTo(1);
        assertThat(detail.path("progress").path("answered").asInt()).isEqualTo(3);
        assertThat(detail.path("progress").path("remainingMax").asInt()).isEqualTo(6);
        assertThat(put(run.id(), iid, owner, "excluded", body(7, code, "new"), 422).path("code").asText()).isEqualTo("QUESTION_NOT_EFFECTIVE");
        assertThat(put(run.id(), iid, owner, "replay-alias", body(5, code, "followup-m"), 200)).isEqualTo(saved);
        JsonNode restored = answer(run.id(), three, 7, 1);
        assertThat(restored.path("branchChanges").path("restoredIssuedIds").size()).isEqualTo(3);
        assertThat(row(getJson("/" + run.id(), owner, 200), iid).path("effective").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT snapshot_json FROM exploration_issued_question WHERE id=?", String.class, iid)).isEqualTo(snapshot);
        assertThat(jdbc.queryForObject("SELECT ordinal FROM exploration_issued_question WHERE id=?", Integer.class, iid)).isEqualTo(ordinal);
        assertThat(count("exploration_issued_question", "session_id=?", run.id())).isEqualTo(36);
    }

    @Test void fullStageOneUsesHttpAnswersAndConfirmationDoesNotExcludeItself() throws Exception {
        Run run = issued(); long revision = 1;
        for (JsonNode q : run.questions()) revision = answer(run.id(), q, revision, 0).path("revision").asLong();
        JsonNode next = postJson(run.id() + "/next", owner, "confirm", mapper.writeValueAsString(Map.of("expectedRevision", revision)), 200);
        revision = next.path("revision").asLong();
        assertThat(next.path("issuedQuestions").size()).isEqualTo(6);
        for (JsonNode q : next.path("issuedQuestions")) {
            long iid = q.path("issuedId").asLong();
            String zero = numericCode(q, 0);
            revision = put(run.id(), iid, owner, UUID.randomUUID().toString(), body(revision, zero, UUID.randomUUID().toString()), 200).path("revision").asLong();
            assertThat(row(getJson("/" + run.id(), owner, 200), iid).path("effective").asBoolean()).isTrue();
        }
        JsonNode detail = getJson("/" + run.id(), owner, 200);
        assertThat(detail.path("nextAction").asText()).isEqualTo("ADVANCE");
        assertThat(detail.path("progress").path("answered").asInt()).isEqualTo(39);
        assertThat(detail.path("session").path("revision").asLong()).isEqualTo(revision);
        JsonNode repeated = postJson(run.id() + "/next", owner, "done", mapper.writeValueAsString(Map.of("expectedRevision", revision)), 200);
        assertThat(repeated.path("issuedQuestions").isEmpty()).isTrue();
        assertThat(repeated.path("revision").asLong()).isEqualTo(revision);

        JsonNode confirmation = next.path("issuedQuestions").get(0);
        String metric = snapshot(confirmation).path("question").path("metric_code").asText();
        JsonNode screen = null;
        for (JsonNode q : run.questions()) {
            JsonNode source = snapshot(q).path("question");
            if (source.path("module_code").asText().equals("INTEREST_SCREEN") && source.path("metric_code").asText().equals(metric)) screen = q;
        }
        assertThat(screen).isNotNull();
        JsonNode changed = put(run.id(), screen.path("issuedId").asLong(), owner, "screen-edit",
                body(revision, numericCode(screen, 0), "screen-edit"), 200);
        assertThat(changed.path("branchChanges").path("budgetLimited").asBoolean()).isTrue();
        assertThat(row(getJson("/" + run.id(), owner, 200), confirmation.path("issuedId").asLong()).path("effective").asBoolean()).isFalse();
        JsonNode restored = put(run.id(), screen.path("issuedId").asLong(), owner, "screen-restore",
                body(changed.path("revision").asLong(), numericCode(screen, 100), "screen-restore"), 200);
        JsonNode restoredRow = row(getJson("/" + run.id(), owner, 200), confirmation.path("issuedId").asLong());
        assertThat(restoredRow.path("effective").asBoolean()).isTrue();
        assertThat(restoredRow.path("answerCode").asText()).isEqualTo(numericCode(confirmation, 0));
        assertThat(restored.path("progress").path("remainingMax").asInt()).isEqualTo(3);
        assertThat(count("exploration_issued_question", "session_id=?", run.id())).isEqualTo(39);
    }

    @Test void unsureIsStoredAndCountsAsSubmittedWithoutManufacturingNumericValue() throws Exception {
        Run run = issued(); JsonNode q = question(run, "STATE-01");
        JsonNode response = put(run.id(), q.path("issuedId").asLong(), owner, "unsure", body(1, "UNSURE", "m"), 200);
        assertThat(response.path("progress").path("answered").asInt()).isEqualTo(1);
        assertThat(response.path("optionCode").asText()).isEqualTo("UNSURE");
        assertThat(response.toString()).doesNotContain("numeric_value", "meaning_code", "scoring_role");
    }

    @Test void terminalStatesRejectNewAnswersButSuccessfulMutationStillReplays() throws Exception {
        Run run = issued(); JsonNode q = run.questions().get(0); long iid = q.path("issuedId").asLong();
        String code = q.path("options").get(0).path("code").asText();
        JsonNode saved = put(run.id(), iid, owner, "answer", body(1, code, "m"), 200);
        postJson(run.id() + "/abandon", owner, "abandon", "{\"expectedRevision\":2}", 200);
        assertThat(put(run.id(), iid, owner, "retry-alias", body(1, code, "m"), 200)).isEqualTo(saved);
        assertThat(put(run.id(), iid, owner, "late", body(3, code, "late"), 409).path("code").asText()).isEqualTo("SESSION_ABANDONED");
        jdbc.update("UPDATE exploration_session SET status='completed',completed_at=NOW() WHERE id=?", run.id());
        assertThat(put(run.id(), iid, owner, "late-complete", body(3, code, "late-complete"), 409).path("code").asText()).isEqualTo("SESSION_ALREADY_COMPLETED");
    }

    @Test void snapshotTamperingIsNotAcceptedAsIssuedOption() throws Exception {
        Run run = issued(); long iid = run.questions().get(0).path("issuedId").asLong();
        jdbc.update("UPDATE exploration_issued_question SET snapshot_json=JSON_SET(snapshot_json,'$.options[0].option_code','INJECTED') WHERE id=?", iid);
        assertThat(put(run.id(), iid, owner, "tampered", body(1, "INJECTED", "m"), 503).path("code").asText()).isEqualTo("CATALOG_INTEGRITY_ERROR");
        assertThat(count("exploration_answer_revision", "issued_id=?", iid)).isZero();
    }

    @Test void failedRevisionInsertRestoresOldCurrentAndSameKeyCanRetry() throws Exception {
        Run run = issued(); JsonNode q = run.questions().get(0); long iid = q.path("issuedId").asLong();
        answer(run.id(), q, 1, 0);
        String code = q.path("options").get(1).path("code").asText(), request = body(2, code, "retry");
        jdbc.execute("CREATE TRIGGER test_answer_insert_failure BEFORE INSERT ON exploration_answer_revision FOR EACH ROW "
                + "BEGIN IF NEW.answer_revision=2 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic answer failure'; END IF; END");
        try {
            put(run.id(), iid, owner, "retry", request, 500);
            assertThat(count("exploration_answer_revision", "issued_id=? AND is_current=true AND answer_revision=1", iid)).isEqualTo(1);
            assertThat(count("exploration_answer_revision", "issued_id=?", iid)).isEqualTo(1);
            assertThat(count("exploration_idempotency", "user_id=? AND idempotency_key='retry'", OWNER)).isZero();
            assertThat(getJson("/" + run.id(), owner, 200).path("session").path("revision").asLong()).isEqualTo(2);
        } finally { jdbc.execute("DROP TRIGGER test_answer_insert_failure"); }
        assertThat(put(run.id(), iid, owner, "retry", request, 200).path("answerRevision").asInt()).isEqualTo(2);
    }

    @Test void commitFailureRollsBackAnswerRevisionBranchChangesAndSuccessReservationTogether() throws Exception {
        Run run = issued(); JsonNode one = question(run, "STATE-01"), three = question(run, "STATE-03"), six = question(run, "STATE-06");
        answer(run.id(), one, 1, 0); answer(run.id(), three, 2, 1); answer(run.id(), six, 3, 0);
        postJson(run.id() + "/next", owner, "extra", "{\"expectedRevision\":4}", 200);
        String request = body(5, three.path("options").get(0).path("code").asText(), "commit-retry");
        jdbc.execute("CREATE TRIGGER test_answer_commit_failure BEFORE UPDATE ON exploration_idempotency FOR EACH ROW "
                + "BEGIN IF NEW.operation='ANSWER' AND NEW.response_status IS NOT NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic commit failure'; END IF; END");
        try {
            put(run.id(), three.path("issuedId").asLong(), owner, "commit-retry", request, 500);
            JsonNode detail = getJson("/" + run.id(), owner, 200);
            assertThat(detail.path("session").path("revision").asLong()).isEqualTo(5);
            assertThat(row(detail, three.path("issuedId").asLong()).path("answerRevision").asInt()).isEqualTo(1);
            assertThat(count("exploration_issued_question", "session_id=? AND effective=true", run.id())).isEqualTo(36);
            assertThat(count("exploration_idempotency", "user_id=? AND idempotency_key='commit-retry'", OWNER)).isZero();
        } finally { jdbc.execute("DROP TRIGGER test_answer_commit_failure"); }
        assertThat(put(run.id(), three.path("issuedId").asLong(), owner, "commit-retry", request, 200)
                .path("branchChanges").path("excludedIssuedIds").size()).isEqualTo(3);
    }

    @Test void idempotencyAndMutationIdentifiersAreScopedToTheIssuedResource() throws Exception {
        Run run = issued(); JsonNode first = run.questions().get(0), second = run.questions().get(1);
        JsonNode a = put(run.id(), first.path("issuedId").asLong(), owner, "shared-key",
                body(1, first.path("options").get(0).path("code").asText(), "shared-mutation"), 200);
        JsonNode b = put(run.id(), second.path("issuedId").asLong(), owner, "shared-key",
                body(2, second.path("options").get(0).path("code").asText(), "shared-mutation"), 200);
        assertThat(b.path("revision").asLong()).isEqualTo(a.path("revision").asLong() + 1);
        assertThat(count("exploration_idempotency", "user_id=? AND operation='ANSWER' AND idempotency_key='shared-key'", OWNER)).isEqualTo(2);
    }

    @Test void concurrentSameMutationWithDifferentHeadersCommitsExactlyOneAnswer() throws Exception {
        Run run = issued(); JsonNode q = run.questions().get(0); long iid = q.path("issuedId").asLong();
        String code = q.path("options").get(0).path("code").asText();
        var replies = parallel(() -> service.answer(OWNER, run.id(), iid, UUID.randomUUID().toString(), 1, code, "same-mutation"));
        assertThat(replies.get(0).body()).isEqualTo(replies.get(1).body());
        assertThat(count("exploration_answer_revision", "issued_id=?", iid)).isEqualTo(1);
        assertThat(getJson("/" + run.id(), owner, 200).path("session").path("revision").asLong()).isEqualTo(2);
    }

    @Test void concurrentDifferentAnswersWithSameExpectedRevisionYieldOneSuccessAndOneConflict() throws Exception {
        Run run = issued(); JsonNode q = run.questions().get(0); long iid = q.path("issuedId").asLong();
        String code = q.path("options").get(0).path("code").asText();
        var replies = parallel(() -> {
            try { service.answer(OWNER, run.id(), iid, UUID.randomUUID().toString(), 1, code, UUID.randomUUID().toString()); return "SUCCESS"; }
            catch (ExplorationApiException e) { return e.getCode(); }
        });
        assertThat(replies).containsExactlyInAnyOrder("SUCCESS", "STALE_REVISION");
        assertThat(count("exploration_answer_revision", "issued_id=? AND is_current=true", iid)).isEqualTo(1);
    }

    @Test void withdrawalPurgesAllAnswerRevisionsAndRejectsOldJwt() throws Exception {
        Run run = issued(); JsonNode q = run.questions().get(0); long iid = q.path("issuedId").asLong();
        answer(run.id(), q, 1, 0); answer(run.id(), q, 2, 1);
        deletion.delete(OWNER);
        assertThat(count("exploration_answer_revision", "issued_id=?", iid)).isZero();
        put(run.id(), iid, owner, "late", body(3, "BROAD", "late"), 401);
    }

    @Test void strictAnswerBodyAndHeaderAreRequired() throws Exception {
        Run run = issued(); long iid = run.questions().get(0).path("issuedId").asLong();
        String path = API + "/" + run.id() + "/answers/" + iid;
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(path).header("Authorization", owner).contentType(MediaType.APPLICATION_JSON).content(body(1, "BROAD", "m")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_REQUEST"));
        for (String invalid : List.of("{}", "{\"expectedRevision\":1,\"optionCode\":\"BROAD\"}",
                "{\"expectedRevision\":1,\"optionCode\":\"\",\"clientMutationId\":\"m\"}",
                "{\"expectedRevision\":1,\"optionCode\":\"BROAD\",\"clientMutationId\":\"bad key\"}",
                "{\"expectedRevision\":1.5,\"optionCode\":\"BROAD\",\"clientMutationId\":\"m\"}",
                "{\"expectedRevision\":\"1\",\"optionCode\":\"BROAD\",\"clientMutationId\":\"m\"}",
                "{\"expectedRevision\":1,\"optionCode\":\"BROAD\",\"clientMutationId\":\"m\",\"numericValue\":100}",
                "{\"expectedRevision\":1,\"optionCode\":\"BROAD\",\"clientMutationId\":\"m\",\"userId\":8200000002}"))
            put(run.id(), iid, owner, "invalid", invalid, 400);
        assertThat(count("exploration_answer_revision", "issued_id=?", iid)).isZero();
    }

    private JsonNode question(Run run, String id) {
        for (JsonNode q : run.questions()) if (q.path("questionId").asText().equals(id)) return q;
        throw new AssertionError("Not issued: " + id);
    }
    private JsonNode snapshot(JsonNode q) throws Exception {
        return mapper.readTree(jdbc.queryForObject("SELECT snapshot_json FROM exploration_issued_question WHERE id=?", String.class, q.path("issuedId").asLong()));
    }
    private String numericCode(JsonNode q, int value) throws Exception {
        for (JsonNode o : snapshot(q).path("options")) if (o.path("numeric_value").asText().equals(Integer.toString(value))) return o.path("option_code").asText();
        throw new AssertionError("No numeric option in pinned snapshot");
    }
    private JsonNode row(JsonNode detail, long iid) {
        for (JsonNode row : detail.path("issued")) if (row.path("question").path("issuedId").asLong() == iid) return row;
        throw new AssertionError("No saved question: " + iid);
    }
    private JsonNode answer(long sessionId, JsonNode q, long revision, int optionIndex) throws Exception {
        return put(sessionId, q.path("issuedId").asLong(), owner, UUID.randomUUID().toString(),
                body(revision, q.path("options").get(optionIndex).path("code").asText(), UUID.randomUUID().toString()), 200);
    }
    private String body(long revision, String code, String mutationId) throws Exception {
        return mapper.writeValueAsString(Map.of("expectedRevision", revision, "optionCode", code, "clientMutationId", mutationId));
    }
    private JsonNode put(long sessionId, long iid, String token, String key, String body, int status) throws Exception {
        return response(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(API + "/" + sessionId + "/answers/" + iid)
                .header("Authorization", token).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body), status);
    }
}
