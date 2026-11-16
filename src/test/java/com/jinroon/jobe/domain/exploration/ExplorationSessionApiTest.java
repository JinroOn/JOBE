package com.jinroon.jobe.domain.exploration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import com.jinroon.jobe.domain.exploration.service.ExplorationSessionService;

class ExplorationSessionApiTest extends ExplorationApiTestSupport {
    @Test void startsWithSixPinnedVersionsAndReplaysOriginalResponse() throws Exception {
        JsonNode start = start("start");
        assertThat(start.path("revision").asLong()).isZero();
        assertThat(start.path("stage").asInt()).isEqualTo(1);
        assertThat(start.path("versions").size()).isEqualTo(6);
        assertThat(start.path("versions").path("semanticContract").asText()).isEqualTo("SEMANTIC_CONTRACT_V1_0");
        long id = start.path("sessionId").asLong();
        postJson(id + "/next", owner, "next", "{\"expectedRevision\":0}", 200);
        assertThat(postJson("", owner, "start", START, 201)).isEqualTo(start);
        assertThat(count("exploration_session", "user_id=?", OWNER)).isEqualTo(1);
    }

    @Test void differentStartKeyReturnsExistingIdAndRollsBackReservation() throws Exception {
        long id = start("start").path("sessionId").asLong();
        JsonNode conflict = postJson("", owner, "new-start", START, 409);
        assertThat(conflict.path("code").asText()).isEqualTo("EXPLORATION_IN_PROGRESS");
        assertThat(conflict.path("existingSessionId").asLong()).isEqualTo(id);
        assertThat(count("exploration_idempotency", "user_id=?", OWNER)).isEqualTo(1);
    }

    @Test void issuesCoreSnapshotWithoutLeakingInternalScoringMetadata() throws Exception {
        long id = start("start").path("sessionId").asLong();
        JsonNode next = postJson(id + "/next", owner, "next", "{\"expectedRevision\":0}", 200);
        assertThat(next.path("issuedQuestions").size()).isEqualTo(33);
        assertThat(next.path("revision").asLong()).isEqualTo(1);
        assertThat(next.path("progress").path("stageMax").asInt()).isEqualTo(42);
        assertThat(next.path("progress").path("remainingMax").asInt()).isEqualTo(9);
        assertThat(next.path("nextAction").asText()).isEqualTo("ANSWER");
        assertThat(next.toString()).doesNotContain("numeric_value", "scoring_role", "meaning_code", "activation_condition", "sourceHashes");
        JsonNode first = next.path("issuedQuestions").get(0);
        assertThat(first.path("text").asText()).isNotBlank();
        assertThat(first.path("comparisonSet").isNull()).isTrue();
        assertThat(first.path("options").get(0).path("order").asInt()).isEqualTo(1);
        String snapshot = jdbc.queryForObject("SELECT snapshot_json FROM exploration_issued_question WHERE id=?", String.class, first.path("issuedId").asLong());
        assertThat(snapshot).contains("scoring_role", "numeric_value", "sourceHashes", "semanticContract", "bundleHash");
        assertThat(count("exploration_issued_question", "session_id=?", id)).isEqualTo(33);
    }

    @Test void repeatedNextDoesNotReissueOrIncreaseRevisionWithoutChanges() throws Exception {
        long id = start("start").path("sessionId").asLong();
        JsonNode next = postJson(id + "/next", owner, "next", "{\"expectedRevision\":0}", 200);
        assertThat(postJson(id + "/next", owner, "next", "{\"expectedRevision\":0}", 200)).isEqualTo(next);
        assertThat(postJson(id + "/next", owner, "another", "{\"expectedRevision\":1}", 200)).isEqualTo(next);
        assertThat(count("exploration_issued_question", "session_id=?", id)).isEqualTo(33);
    }

    @Test void staleAndConflictingRequestsHaveSeparateErrorsAndNoReservationLeak() throws Exception {
        long id = start("start").path("sessionId").asLong();
        postJson(id + "/next", owner, "next", "{\"expectedRevision\":0}", 200);
        assertThat(postJson(id + "/next", owner, "next", "{\"expectedRevision\":1}", 409).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(postJson(id + "/next", owner, "stale", "{\"expectedRevision\":0}", 409).path("code").asText()).isEqualTo("STALE_REVISION");
        assertThat(count("exploration_idempotency", "user_id=?", OWNER)).isEqualTo(2);
    }

    @Test void listAndResumeReturnOnlyOwnedSessionsAndSavedIssuedIds() throws Exception {
        long id = start("start").path("sessionId").asLong();
        JsonNode next = postJson(id + "/next", owner, "next", "{\"expectedRevision\":0}", 200);
        JsonNode detail = getJson("/" + id, owner, 200);
        assertThat(detail.path("issued").size()).isEqualTo(33);
        assertThat(detail.path("issued").get(0).path("question")).isEqualTo(next.path("issuedQuestions").get(0));
        assertThat(detail.path("issued").get(0).path("answerCode").isNull()).isTrue();
        assertThat(getJson("", owner, 200).get(0).path("sessionId").asLong()).isEqualTo(id);
        assertThat(getJson("", other, 200).size()).isZero();
        assertThat(getJson("/" + id, other, 404).path("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(postJson(id + "/next", other, "foreign", "{\"expectedRevision\":0}", 404).path("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test void abandonAllowsNewSessionAndReplaysAfterTerminalState() throws Exception {
        long id = start("start").path("sessionId").asLong();
        JsonNode abandoned = postJson(id + "/abandon", owner, "abandon", "{\"expectedRevision\":0}", 200);
        assertThat(abandoned.path("status").asText()).isEqualTo("abandoned");
        assertThat(abandoned.path("revision").asLong()).isEqualTo(1);
        assertThat(postJson(id + "/abandon", owner, "abandon", "{\"expectedRevision\":0}", 200)).isEqualTo(abandoned);
        assertThat(postJson(id + "/next", owner, "late", "{\"expectedRevision\":1}", 409).path("code").asText()).isEqualTo("SESSION_ABANDONED");
        assertThat(getJson("/" + id, owner, 200).path("nextAction").isNull()).isTrue();
        assertThat(start("second").path("sessionId").asLong()).isNotEqualTo(id);
    }

    @Test void completedSessionRejectsNewMutation() throws Exception {
        long id = start("start").path("sessionId").asLong();
        jdbc.update("UPDATE exploration_session SET status='completed',completed_at=NOW() WHERE id=?", id);
        assertThat(postJson(id + "/next", owner, "late", "{\"expectedRevision\":0}", 409).path("code").asText()).isEqualTo("SESSION_ALREADY_COMPLETED");
    }

    @Test void fixedSnapshotSurvivesSourceQuestionEditsBeforeFirstIssuance() throws Exception {
        long id = start("start").path("sessionId").asLong();
        long catalog = jdbc.queryForObject("SELECT question_catalog_id FROM exploration_session WHERE id=?", Long.class, id);
        String original = jdbc.queryForObject("SELECT metadata_json FROM exploration_question WHERE catalog_id=? AND question_id='STATE-01'", String.class, catalog);
        try {
            jdbc.update("UPDATE exploration_question SET metadata_json=JSON_SET(metadata_json,'$.question_text','CHANGED AFTER START') WHERE catalog_id=? AND question_id='STATE-01'", catalog);
            JsonNode next = postJson(id + "/next", owner, "next", "{\"expectedRevision\":0}", 200);
            assertThat(next.toString()).doesNotContain("CHANGED AFTER START");
            assertThat(getJson("/" + id, owner, 200).toString()).doesNotContain("CHANGED AFTER START");
        } finally { jdbc.update("UPDATE exploration_question SET metadata_json=? WHERE catalog_id=? AND question_id='STATE-01'", original, catalog); }
    }

    @Test void sharedSnapshotTamperingFailsClosedAndRollsBackNext() throws Exception {
        long id = start("start").path("sessionId").asLong();
        long unrelated = postJson("", other, "unrelated", START, 201).path("sessionId").asLong();
        String original = jdbc.queryForObject("SELECT s.payload_json FROM exploration_runtime_snapshot s JOIN exploration_session_pin p ON p.snapshot_hash=s.content_hash WHERE p.session_id=?", String.class, unrelated);
        try (var restored = preserveRuntimeSnapshot(id)) {
            jdbc.update("UPDATE exploration_runtime_snapshot s JOIN exploration_session_pin p ON p.snapshot_hash=s.content_hash SET s.payload_json=JSON_SET(s.payload_json,'$.tampered',true) WHERE p.session_id=?", id);
            assertThat(postJson(id + "/next", owner, "next", "{\"expectedRevision\":0}", 503).path("code").asText()).isEqualTo("CATALOG_INTEGRITY_ERROR");
            assertThat(count("exploration_issued_question", "session_id=?", id)).isZero();
            assertThat(count("exploration_idempotency", "user_id=?", OWNER)).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT s.payload_json FROM exploration_runtime_snapshot s JOIN exploration_session_pin p ON p.snapshot_hash=s.content_hash WHERE p.session_id=?", String.class, unrelated)).isEqualTo(original);
        postJson(unrelated + "/next", other, "after-restoration", "{\"expectedRevision\":0}", 200);
    }

    @Test void sharedSnapshotIsRestoredEvenWhenTamperingAssertionFails() throws Exception {
        long id = start("start").path("sessionId").asLong();
        assertThatThrownBy(() -> {
            try (var restored = preserveRuntimeSnapshot(id)) {
                jdbc.update("UPDATE exploration_runtime_snapshot s JOIN exploration_session_pin p ON p.snapshot_hash=s.content_hash SET s.payload_json=JSON_OBJECT('changed',true) WHERE p.session_id=?", id);
                throw new AssertionError("synthetic failed assertion");
            }
        }).isInstanceOf(AssertionError.class).hasMessage("synthetic failed assertion");
        postJson(id + "/next", owner, "after-restoration", "{\"expectedRevision\":0}", 200);
    }

    @Test void sharedSnapshotsDeduplicateAndWithdrawalRemovesOnlyPersonalGraph() throws Exception {
        long id = start("start").path("sessionId").asLong();
        long unrelated = postJson("", other, "start", START, 201).path("sessionId").asLong();
        String hash = jdbc.queryForObject("SELECT snapshot_hash FROM exploration_session_pin WHERE session_id=?", String.class, id);
        assertThat(jdbc.queryForObject("SELECT snapshot_hash FROM exploration_session_pin WHERE session_id=?", String.class, unrelated)).isEqualTo(hash);
        postJson(id + "/next", owner, "next", "{\"expectedRevision\":0}", 200);
        assertThat(count("exploration_runtime_snapshot", "content_hash=?", hash)).isEqualTo(1);
        deletion.delete(OWNER);
        assertThat(count("exploration_session_pin", "session_id=?", id)).isZero();
        assertThat(count("exploration_issued_question", "session_id=?", id)).isZero();
        assertThat(count("exploration_idempotency", "user_id=?", OWNER)).isZero();
        assertThat(count("exploration_runtime_snapshot", "content_hash=?", hash)).isEqualTo(1);
        assertThat(getJson("", other, 200).size()).isEqualTo(1);
        getJson("/" + id, owner, 401); // old JWT cannot restore the deleted account
    }

    @Test void contentGateRejectsUnreviewedChildWithoutLeavingSessionOrPin() throws Exception {
        postJson("", other, "existing-session", START, 201);
        long child = jdbc.queryForObject("SELECT q.id FROM exploration_question q JOIN exploration_catalog c ON c.id=q.catalog_id WHERE c.version='QUESTION_CATALOG_V1_1' AND q.question_id='STATE-01'", Long.class);
        int pinsBefore = count("exploration_session_pin", "1=1");
        String original = jdbc.queryForObject("SELECT implementation_review_status FROM exploration_question WHERE id=?", String.class, child);
        try {
            jdbc.update("UPDATE exploration_question SET implementation_review_status='REJECTED' WHERE id=?", child);
            assertThat(postJson("", owner, "start", START, 503).path("code").asText()).isEqualTo("NO_ACTIVE_CATALOG");
            assertThat(count("exploration_session", "user_id=?", OWNER)).isZero();
            assertThat(count("exploration_idempotency", "user_id=?", OWNER)).isZero();
            assertThat(count("exploration_session_pin", "1=1")).isEqualTo(pinsBefore);
        } finally { jdbc.update("UPDATE exploration_question SET implementation_review_status=? WHERE id=?", original, child); }
    }

    @Test void usersOutsideServerAllowlistCannotStartOrList() throws Exception {
        assertThat(postJson("", outside, "start", START, 503).path("code").asText()).isEqualTo("NO_ACTIVE_CATALOG");
        getJson("", outside, 503);
        assertThat(count("exploration_session", "user_id=?", OUTSIDE)).isZero();
    }

    @Test void authenticationIsRequired() throws Exception {
        mvc.perform(post(API).contentType(MediaType.APPLICATION_JSON).content(START).header("Idempotency-Key", "start")).andExpect(status().isUnauthorized());
    }

    @Test void rejectsMissingHeaderMalformedBodyAndClientOwnedModeOrVersionFields() throws Exception {
        mvc.perform(post(API).header("Authorization", owner).contentType(MediaType.APPLICATION_JSON).content(START))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("code").value("INVALID_REQUEST"));
        for (String body : List.of("{}", "null", "{", "{\"diagnosisType\":\"LEGACY\"}",
                "{\"diagnosisType\":\"EXPLORATION_V1\",\"runMode\":\"PUBLIC\"}",
                "{\"diagnosisType\":\"EXPLORATION_V1\",\"userId\":8200000002}",
                "{\"diagnosisType\":\"EXPLORATION_V1\",\"versions\":{}}")) postJson("", owner, "invalid", body, 400);
        postJson("", owner, "bad key", START, 400);
        postJson("", owner, "x".repeat(129), START, 400);
    }

    @Test void rejectsMissingOrNegativeRevisionAndUnknownMutationFields() throws Exception {
        long id = start("start").path("sessionId").asLong();
        for (String body : List.of("{}", "{\"expectedRevision\":-1}", "{\"expectedRevision\":0,\"stage\":2}",
                "{\"expectedRevision\":0.5}", "{\"expectedRevision\":\"0\"}", "{\"expectedRevision\":true}",
                "{\"expectedRevision\":9223372036854775808}"))
            postJson(id + "/next", owner, "invalid", body, 400);
        assertThat(count("exploration_issued_question", "session_id=?", id)).isZero();
    }

    @Test void corsPreflightAcceptsIdempotencyHeader() throws Exception {
        mvc.perform(options(API).header("Origin", "http://localhost:5173").header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Authorization, Content-Type, Idempotency-Key"))
                .andExpect(status().isOk());
    }

    @Test void concurrentSameKeyStartsReplayOneCommittedSession() throws Exception {
        List<ExplorationSessionService.Reply> replies = parallel(() -> service.start(OWNER, "same", "EXPLORATION_V1"));
        assertThat(replies.get(0).body()).isEqualTo(replies.get(1).body());
        assertThat(count("exploration_session", "user_id=?", OWNER)).isEqualTo(1);
        assertThat(count("exploration_idempotency", "user_id=?", OWNER)).isEqualTo(1);
    }

    @Test void concurrentNextReplaysOneBatchWithStableOrdinals() throws Exception {
        long id = start("start").path("sessionId").asLong();
        var replies = parallel(() -> service.next(OWNER, id, "same", 0));
        assertThat(replies.get(0).body()).isEqualTo(replies.get(1).body());
        assertThat(count("exploration_issued_question", "session_id=?", id)).isEqualTo(33);
        assertThat(jdbc.queryForObject("SELECT MAX(ordinal) FROM exploration_issued_question WHERE session_id=?", Integer.class, id)).isEqualTo(33);
    }

    @Test void databaseRejectsDuplicateOrdinaryQuestionEvenWithNewOrdinal() throws Exception {
        long id = start("start").path("sessionId").asLong();
        postJson(id + "/next", owner, "next", "{\"expectedRevision\":0}", 200);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO exploration_issued_question(session_id,question_id,question_version,stage,ordinal,snapshot_json,shown_at) "
                        + "SELECT session_id,question_id,question_version,stage,99,snapshot_json,NOW() FROM exploration_issued_question WHERE session_id=? AND ordinal=1", id))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("uq_exploration_issued_identity");
    }

    @Test void submittedScreeningActivatesRealConfirmationQuestionsWithinBudget() throws Exception {
        long id = start("start").path("sessionId").asLong();
        JsonNode core = postJson(id + "/next", owner, "next", "{\"expectedRevision\":0}", 200);
        // Seed valid committed answers; answer submission API itself is deliberately a later unit.
        for (JsonNode q : core.path("issuedQuestions")) {
            String code = q.path("options").get(0).path("code").asText();
            jdbc.update("INSERT INTO exploration_answer_revision(issued_id,answer_revision,option_code,is_current,client_mutation_id,mutation_request_hash,mutation_response_json,answered_at) "
                    + "VALUES (?,1,?,true,?,?,JSON_OBJECT(),NOW())", q.path("issuedId").asLong(), code, UUID.randomUUID().toString(), new byte[32]);
        }
        JsonNode next = postJson(id + "/next", owner, "conditional", "{\"expectedRevision\":1}", 200);
        assertThat(next.path("issuedQuestions").size()).isEqualTo(6);
        assertThat(next.path("revision").asLong()).isEqualTo(2);
        assertThat(next.path("progress").path("answered").asInt()).isEqualTo(33);
        assertThat(next.path("progress").path("issued").asInt()).isEqualTo(39);
        assertThat(count("exploration_issued_question", "session_id=?", id)).isEqualTo(39);
        for (JsonNode q : next.path("issuedQuestions")) assertThat(q.path("questionId").asText()).startsWith("INT-");
    }

    @Test void partialIssuanceFailureRollsBackEntireBatchAndCanRetrySameKey() throws Exception {
        long id = start("start").path("sessionId").asLong();
        jdbc.execute("CREATE TRIGGER test_issuance_failure BEFORE INSERT ON exploration_issued_question FOR EACH ROW "
                + "BEGIN IF NEW.ordinal=3 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic issuance failure'; END IF; END");
        try {
            postJson(id + "/next", owner, "retry", "{\"expectedRevision\":0}", 500);
            assertThat(count("exploration_issued_question", "session_id=?", id)).isZero();
            assertThat(count("exploration_idempotency", "user_id=?", OWNER)).isEqualTo(1);
            assertThat(getJson("/" + id, owner, 200).path("session").path("revision").asLong()).isZero();
        } finally { jdbc.execute("DROP TRIGGER test_issuance_failure"); }
        assertThat(postJson(id + "/next", owner, "retry", "{\"expectedRevision\":0}", 200).path("issuedQuestions").size()).isEqualTo(33);
    }

    @Test void concurrentDifferentKeysYieldOneStartAndOneExistingSessionConflict() throws Exception {
        var outcomes = parallel(() -> {
            try { service.start(OWNER, UUID.randomUUID().toString(), "EXPLORATION_V1"); return "CREATED"; }
            catch (com.jinroon.jobe.domain.exploration.api.ExplorationApiException e) { return e.getCode(); }
        });
        assertThat(outcomes).containsExactlyInAnyOrder("CREATED", "EXPLORATION_IN_PROGRESS");
        assertThat(count("exploration_session", "user_id=?", OWNER)).isEqualTo(1);
        assertThat(count("exploration_idempotency", "user_id=?", OWNER)).isEqualTo(1);
    }

}
