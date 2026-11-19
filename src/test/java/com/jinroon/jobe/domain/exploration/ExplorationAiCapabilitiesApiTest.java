package com.jinroon.jobe.domain.exploration;

import com.fasterxml.jackson.databind.JsonNode;
import com.jinroon.jobe.domain.exploration.ai.ExplorationJobClient;
import com.jinroon.jobe.domain.exploration.ai.ExplorationModelAvailability;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

@TestPropertySource(properties = {
        "exploration.jobs.enabled=true",
        "exploration.jobs.model-enabled=true",
        "exploration.jobs.erasure.enabled=true",
        "exploration.jobs.model-name=SYNTHETIC_MODEL",
        "exploration.evidence.enabled=true",
        "exploration.evidence.dataset-version=SYNTHETIC_DATASET",
        "ai.server.url=http://127.0.0.1:1",
        "ai.server.token=SYNTHETIC_INTERNAL_TOKEN"
})
class ExplorationAiCapabilitiesApiTest extends CompletedExplorationTestSupport {
    @Autowired ExplorationModelAvailability availability;
    @Autowired ExplorationJobClient client;

    @Test void candidateResultOffersBothEntryPointsBeforeChoosingSupport() throws Exception {
        long result = completedResult(true);
        var snapshot = resultSnapshot(result);
        var detail = resultDetail(result);
        assertAvailability(detail, true, true);
        long session = detail.path("sessionId").asLong();
        assertAvailability(getJson("/" + session + "/result", owner, 200), true, true);
        assertThat(snapshot.has("capabilities")).isFalse();
        assertThat(resultSnapshot(result)).isEqualTo(snapshot);
        assertThat(count("exploration_generation_task", "user_id=?", OWNER)).isZero();
    }

    @Test void candidateFreeResultOffersOnlyConsultation() throws Exception {
        var detail = resultDetail(completedResult(false));
        assertThat(detail.path("status").asText()).isEqualTo("NO_SUPPORTED_CANDIDATE");
        assertAvailability(detail, false, true);
    }

    @Test void existingResultUsesCurrentSettingsWithoutRewritingTheSnapshot() throws Exception {
        long result = completedResult(true);
        var snapshot = resultSnapshot(result);
        assertUnavailableWith(result, availability, "modelEnabled", false);
        assertUnavailableWith(result, availability, "erasureEnabled", false);
        assertUnavailableWith(result, availability, "explorationEnabled", false);
        assertUnavailableWith(result, availability, "evidenceEnabled", false);
        assertUnavailableWith(result, availability, "runMode", "PUBLIC");
        assertUnavailableWith(result, client, "enabled", false);
        assertUnavailableWith(result, client, "base", "");
        assertUnavailableWith(result, client, "token", "");
        assertUnavailableWith(result, client, "datasetVersion", "invalid version");
        assertUnavailableWith(result, client, "modelName", "");
        assertAvailability(resultDetail(result), true, true);
        assertThat(resultSnapshot(result)).isEqualTo(snapshot);
    }

    private void assertUnavailableWith(long result, Object component, String field, Object value) throws Exception {
        Object previous = ReflectionTestUtils.getField(component, field);
        try {
            ReflectionTestUtils.setField(component, field, value);
            assertAvailability(resultDetail(result), false, false);
        } finally {
            ReflectionTestUtils.setField(component, field, previous);
        }
    }

    private JsonNode resultDetail(long result) throws Exception {
        return response(get("/api/exploration/v1/results/" + result).header("Authorization", owner), 200);
    }

    private void assertAvailability(JsonNode result, boolean plan, boolean consultation) {
        var capabilities = result.path("capabilities");
        assertThat(capabilities.path("planAvailable").booleanValue()).isEqualTo(plan);
        assertThat(capabilities.path("consultationAvailable").booleanValue()).isEqualTo(consultation);
        assertThat(capabilities.path("planAvailable").isBoolean()).isTrue();
        assertThat(capabilities.path("consultationAvailable").isBoolean()).isTrue();
    }
}
