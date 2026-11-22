package com.jinroon.jobe.domain.exploration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.service.ExplorationResultStore;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ExplorationResultDisplayApiTest extends CompletedExplorationTestSupport {
    @Autowired ExplorationResultStore store;

    @Test void completionFreezesDisplayAndBothRoutesExposeNamesEvidenceReasonsAndNoRawScores() throws Exception {
        long rid = completedResult(true);
        JsonNode stored = resultSnapshot(rid), result = result(rid, owner, 200);
        assertThat(stored.path("display").path("version").asText()).isEqualTo("RESULT_DISPLAY_V1_1");
        assertThat(result.path("displaySource").asText()).isEqualTo("COMPLETION_SNAPSHOT");
        assertThat(result.path("groups").size()).isBetween(1, 3); assertThat(result.path("majors").size()).isBetween(1, 7);
        assertThat(result.path("majors").findValuesAsText("majorCode")).isEqualTo(stored.path("majorSummaries").findValuesAsText("majorCode"));
        for (JsonNode group : result.path("groups")) {
            assertThat(group.path("name").asText()).isNotBlank(); assertThat(group.path("summary").asText()).contains("탐색");
            assertThat(group.path("evidenceLevel").asText()).isIn("CONFIRMED", "LOW_EVIDENCE", "NO_EVIDENCE");
            for (JsonNode nested : group.path("majors")) assertThat(nested.path("groupCode")).isEqualTo(group.path("groupCode"));
        }
        for (JsonNode major : result.path("majors")) {
            assertThat(major.path("name")).isEqualTo(major.path("majorName"));
            assertThat(major.path("reasons").size()).isEqualTo(3);
            assertThat(major.path("reasons").findValuesAsText("kind")).containsExactly("INTEREST", "ACTIVITY", "LEARNING");
            assertThat(major.path("collapsed").asBoolean()).isFalse();
        }
        assertThat(result.path("preparationResponses")).isEqualTo(stored.path("cautions"));
        assertThat(result.toString()).doesNotContain("rankedCandidates", "inputHash", "sourceHashes", "bundleHash", "\"score\"", "\"total\"");
        assertThat(getJson("/" + stored.path("sessionId").asLong() + "/result", owner, 200)).isEqualTo(result);
    }

    @Test void storedCautionsMatchBothDeclaredTagsAndPinnedDatabaseApprovalAndAreAtMostTwo() throws Exception {
        long rid = completedResult(Map.of("INT_ENG", 100, "INT_DAT", 75));
        JsonNode result = result(rid, owner, 200);
        assertThat(result.path("cautionAvailability").asText()).isEqualTo("EVALUATED");
        assertThat(result.path("cautions").size()).isBetween(1, 2);
        for (JsonNode tag : result.path("cautions")) {
            var row = jdbc.queryForMap("SELECT t.implementation_review_status,t.domain_review_status,t.evidence FROM exploration_caution_tag t "
                    + "JOIN exploration_major_mapping m ON m.id=t.mapping_id JOIN exploration_session s ON s.major_mapping_catalog_id=m.catalog_id "
                    + "WHERE s.id=? AND m.major_code=? AND t.tag_code=?", resultSnapshot(rid).path("sessionId").asLong(), tag.path("majorCode").asText(), tag.path("tagCode").asText());
            assertThat(row.get("implementation_review_status")).isEqualTo("APPROVED_FOR_IMPLEMENTATION");
            assertThat(row.get("domain_review_status")).isNotEqualTo("REJECTED");
            assertThat(tag.path("text").asText()).isNotBlank(); assertThat(tag.path("checkAction").asText()).isNotBlank();
            assertThat(tag.path("usage").asText()).isEqualTo("DEV_EXPLORATION_CHECK_ONLY");
        }
    }

    @Test void displayReadsDoNotDependOnAnAlteredRuntimeSnapshotOrModifySealOrResult() throws Exception {
        long rid = completedResult(true), sid = resultSnapshot(rid).path("sessionId").asLong();
        JsonNode original = result(rid, owner, 200);
        String before = jdbc.queryForObject("SELECT result_json FROM exploration_result WHERE id=?", String.class, rid);
        String seal = jdbc.queryForObject("SELECT content_hash FROM exploration_result_seal WHERE result_id=?", String.class, rid);
        try (var restored = preserveRuntimeSnapshot(sid)) {
            jdbc.update("UPDATE exploration_runtime_snapshot s JOIN exploration_session_pin p ON p.snapshot_hash=s.content_hash SET s.payload_json=JSON_OBJECT('changed',true) WHERE p.session_id=?", sid);
            assertThat(result(rid, owner, 200)).isEqualTo(original);
            assertThat(getJson("/" + sid + "/result", owner, 200)).isEqualTo(original);
        }
        assertThat(jdbc.queryForObject("SELECT result_json FROM exploration_result WHERE id=?", String.class, rid)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT content_hash FROM exploration_result_seal WHERE result_id=?", String.class, rid)).isEqualTo(seal);
    }

    @Test void legacySealedResultGetsReadOnlyLabelsButDoesNotInventCautionApproval() throws Exception {
        JsonNode original = resultSnapshot(completedResult(true));
        long sid = start("synthetic-legacy").path("sessionId").asLong();
        ObjectNode old = original.deepCopy(); old.remove("display"); old.put("sessionId", sid);
        // Create a separate synthetic historical result. Never edit an existing sealed result to upgrade it.
        long rid = store.save(sid, old).getId();
        jdbc.update("UPDATE exploration_session SET status='completed',completed_at=NOW() WHERE id=?", sid);
        String before = jdbc.queryForObject("SELECT result_json FROM exploration_result WHERE id=?", String.class, rid);
        String seal = jdbc.queryForObject("SELECT content_hash FROM exploration_result_seal WHERE result_id=?", String.class, rid);
        JsonNode response = result(rid, owner, 200);
        assertThat(response.path("displaySource").asText()).isEqualTo("SEALED_RESULT_PROJECTION");
        assertThat(response.path("groups").get(0).path("name").asText()).isNotBlank();
        assertThat(response.path("cautionAvailability").asText()).isEqualTo("UNAVAILABLE");
        assertThat(response.path("cautionUnavailableReason").asText()).isEqualTo("APPROVAL_SNAPSHOT_NOT_AVAILABLE");
        assertThat(response.path("cautions").isEmpty()).isTrue();
        assertThat(response.path("preparationResponses")).isEqualTo(old.path("cautions"));
        assertThat(jdbc.queryForObject("SELECT result_json FROM exploration_result WHERE id=?", String.class, rid)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT content_hash FROM exploration_result_seal WHERE result_id=?", String.class, rid)).isEqualTo(seal);
        result(rid, other, 404); result(rid, null, 401);
    }

    @Test void noCandidateHasNoInventedCardsAndStillNamesInsufficientInterestAxes() throws Exception {
        long rid = completedResult(false); JsonNode response = result(rid, owner, 200);
        assertThat(response.path("status").asText()).isEqualTo("NO_SUPPORTED_CANDIDATE");
        assertThat(response.path("groups").isEmpty()).isTrue(); assertThat(response.path("majors").isEmpty()).isTrue();
        assertThat(response.path("cautions").isEmpty()).isTrue(); assertThat(response.path("recheckAxes").size()).isEqualTo(14);
        for (JsonNode axis : response.path("recheckAxes")) assertThat(axis.path("name").asText()).isNotBlank();
    }

    @Test void tamperedSealedDisplayCannotBeServedAsAValidResult() throws Exception {
        long rid = completedResult(true);
        jdbc.update("UPDATE exploration_result SET result_json=JSON_SET(result_json,'$.display.groups[0].name','tampered') WHERE id=?", rid);
        assertThat(result(rid, owner, 503).path("code").asText()).isEqualTo("CATALOG_INTEGRITY_ERROR");
    }

    private JsonNode result(long id, String token, int expected) throws Exception {
        var request = get("/api/exploration/v1/results/" + id); if (token != null) request.header("Authorization", token);
        return response(request, expected);
    }
}
