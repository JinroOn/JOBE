package com.jinroon.jobe.domain.exploration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.ai.ExplorationAiContract;
import com.jinroon.jobe.domain.exploration.ai.ExplorationAiInputAdapter;
import com.jinroon.jobe.domain.exploration.ai.ExplorationJobClient;
import com.jinroon.jobe.domain.exploration.ai.ExplorationModelAvailability;
import com.jinroon.jobe.domain.exploration.ai.OwnedGenerationStore;
import com.jinroon.jobe.domain.exploration.ai.OwnedMockExecutionService;
import com.jinroon.jobe.domain.exploration.context.ResultContextService;
import com.jinroon.jobe.domain.exploration.enums.ExplorationEnums.ExplorationRunMode;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@TestPropertySource(properties = "exploration.jobs.dispatch.initial-delay-ms=3600000")
class MajorExplanationApiTest extends CompletedExplorationTestSupport {
    private static final String MAJOR = "MAJOR_ROW_6";
    @MockitoBean ExplorationModelAvailability availability;
    @MockitoBean ExplorationJobClient client;
    @Autowired ExplorationAiContract contract;
    @Autowired OwnedGenerationStore tasks;
    @Autowired OwnedMockExecutionService executor;
    @Autowired ResultContextService contexts;
    @Autowired ExplorationAiInputAdapter adapter;
    @Autowired ExplorationJson json;

    @Test
    void ownerQueuesOnceAndReadsStoredExplanationWithOriginalAnswer() throws Exception {
        when(availability.explanationAvailableFor(ExplorationRunMode.DEV)).thenReturn(true);
        long resultId = completedResult(Map.of("INT_MED", 100, "INT_HUM", 50));
        String endpoint = endpoint(resultId, MAJOR);
        assertThat(response(get(endpoint).header("Authorization", owner), 200).path("status").asText())
                .isEqualTo("NOT_REQUESTED");
        assertThat(response(get(endpoint).header("Authorization", other), 404).path("code").asText())
                .isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(response(post(endpoint(resultId, "MAJOR_ROW_99999")).header("Authorization", owner), 422).path("code").asText())
                .isEqualTo("TARGET_MAJOR_NOT_IN_RESULT");
        for (int n = 0; n < 2; n++) {
            assertThat(response(post(endpoint).header("Authorization", owner), 202).path("status").asText())
                    .isEqualTo("GENERATING");
        }
        var candidates = tasks.explanationCandidates().stream().filter(job -> job.user() == OWNER).toList();
        assertThat(candidates).hasSize(1);
        when(client.command(eq("enqueue"), anyString(), anyString(), any(JsonNode.class)))
                .thenReturn(mapper.createObjectNode().put("status", "QUEUED"));
        when(client.executeModel(anyString(), anyString(), any(JsonNode.class)))
                .thenAnswer(call -> completed(call.getArgument(1), call.getArgument(2)));
        executor.executeQueuedExplanation(candidates.get(0));
        var result = response(get(endpoint).header("Authorization", owner), 200);
        assertThat(result.path("status").asText()).isEqualTo("READY");
        var connection = result.path("connections").get(0);
        assertThat(connection.path("question").asText()).isNotBlank();
        assertThat(connection.path("answer").asText()).isNotBlank();
        assertThat(connection.path("connection").asText()).isEqualTo("글쓰기 수업을 탐색해 볼 수 있어요.");
        assertThat(result.toString()).doesNotContain("referenceKeys", "resultHash", "inputHash");
        assertThat(response(post(endpoint).header("Authorization", owner), 200)).isEqualTo(result);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"OUTPUT_INVALID,true", "INVALID_REQUEST,false"})
    void failedExplanationCanBeRetriedOnce(String failure, boolean succeeds) throws Exception {
        when(availability.explanationAvailableFor(ExplorationRunMode.DEV)).thenReturn(true);
        long resultId = completedResult(Map.of("INT_MED", 100, "INT_HUM", 50));
        String endpoint = endpoint(resultId, MAJOR);
        response(post(endpoint).header("Authorization", owner), 202);
        var original = tasks.explanationCandidates().stream().filter(job -> job.user() == OWNER).findFirst().orElseThrow();
        var lease = tasks.claimModel(OWNER, original.context(), original.purpose(), original.mutation());
        tasks.failed(original, lease.token(), false, failure);

        var failed = response(get(endpoint).header("Authorization", owner), 200);
        assertThat(failed.path("status").asText()).isEqualTo("FAILED");
        assertThat(failed.path("retryAvailable").asBoolean()).isTrue();
        response(post(endpoint + "/retry").header("Authorization", other), 404);
        for (int i = 0; i < 2; i++) {
            assertThat(response(post(endpoint + "/retry").header("Authorization", owner), 202).path("status").asText())
                    .isEqualTo("GENERATING");
        }
        var queued = tasks.explanationCandidates().stream().filter(job -> job.user() == OWNER).toList();
        assertThat(queued).hasSize(1);
        var retry = queued.get(0);
        assertThat(retry.mutation()).isNotEqualTo(original.mutation());
        var secondLease = tasks.claimModel(OWNER, retry.context(), retry.purpose(), retry.mutation());
        if (succeeds) {
            tasks.completeModel(OWNER, retry.context(), retry.purpose(), retry.mutation(), secondLease.token(),
                    completed(retry.mutation(), secondLease.request()));
        } else {
            tasks.failed(retry, secondLease.token(), false, "OUTPUT_INVALID");
        }
        var result = response(get(endpoint).header("Authorization", owner), 200);
        assertThat(result.path("status").asText()).isEqualTo(succeeds ? "READY" : "FAILED");
        assertThat(result.path("retryAvailable").asBoolean()).isFalse();
        assertThat(response(post(endpoint + "/retry").header("Authorization", owner), 200)).isEqualTo(result);
        assertThat(tasks.explanationCandidates()).noneMatch(job -> job.user() == OWNER);
    }

    @Test
    void ownerCanRegenerateAnExplanationFromThePreviousPrompt() throws Exception {
        when(availability.explanationAvailableFor(ExplorationRunMode.DEV)).thenReturn(true);
        long resultId = completedResult(Map.of("INT_MED", 100, "INT_HUM", 50));
        String endpoint = endpoint(resultId, MAJOR);
        var context = contexts.prepareExplanation(OWNER, resultId, MAJOR);
        String legacyMutation = "major-explanation-" + context.contextId();
        tasks.reserveModel(OWNER, context.contextId(), "EXPLANATION", legacyMutation,
                adapter.build(context.snapshot(), legacyMutation));
        assertThat(response(post(endpoint).header("Authorization", owner), 202).path("status").asText())
                .isEqualTo("GENERATING");
        assertThat(tasks.explanationCandidates().stream().filter(job -> job.user() == OWNER)
                .map(OwnedGenerationStore.Candidate::mutation).toList()).containsExactly(legacyMutation);
        var legacyLease = tasks.claimModel(OWNER, context.contextId(), "EXPLANATION", legacyMutation);
        var legacyResult = completed(legacyMutation, legacyLease.request());
        tasks.completeModel(OWNER, context.contextId(), "EXPLANATION", legacyMutation, legacyLease.token(), legacyResult);
        ((ObjectNode) legacyResult.path("providerMetadata")).put("promptVersion", "EXPLORATION_MAJOR_EXPLANATION_V1_4");
        jdbc.update("UPDATE exploration_generation_task SET result_json=?,result_hash=? WHERE user_id=? AND mutation_key=?",
                legacyResult.toString(), json.hex(legacyResult), OWNER, legacyMutation);

        assertThat(response(get(endpoint).header("Authorization", owner), 200).path("status").asText())
                .isEqualTo("NOT_REQUESTED");
        for (int n = 0; n < 2; n++) {
            assertThat(response(post(endpoint).header("Authorization", owner), 202).path("status").asText())
                    .isEqualTo("GENERATING");
        }
        var candidates = tasks.explanationCandidates().stream().filter(job -> job.user() == OWNER).toList();
        assertThat(candidates).hasSize(1);
        var current = candidates.get(0);
        assertThat(current.mutation()).isNotEqualTo(legacyMutation);
        var currentLease = tasks.claimModel(OWNER, current.context(), current.purpose(), current.mutation());
        tasks.completeModel(OWNER, current.context(), current.purpose(), current.mutation(), currentLease.token(),
                completed(current.mutation(), currentLease.request()));

        assertThat(response(get(endpoint).header("Authorization", owner), 200).path("status").asText())
                .isEqualTo("READY");
        assertThat(tasks.completedModel(OWNER, context.contextId(), "EXPLANATION", legacyMutation)
                .path("promptVersion").asText()).isEqualTo("EXPLORATION_MAJOR_EXPLANATION_V1_4");
    }

    @Test
    void disabledFeatureDoesNotReserveAnExplanation() throws Exception {
        long resultId = completedResult(Map.of("INT_MED", 100, "INT_HUM", 50));
        String endpoint = endpoint(resultId, MAJOR);
        assertThat(response(post(endpoint).header("Authorization", owner), 200).path("status").asText()).isEqualTo("UNAVAILABLE");
        assertThat(tasks.explanationCandidates()).noneMatch(job -> job.user() == OWNER);
    }

    private ObjectNode completed(String mutation, JsonNode original) {
        String reference = "ref_" + "a".repeat(64);
        ObjectNode bound = original.deepCopy();
        var evidence = bound.putObject("evidence").put("approvedOnly", true)
                .put("bindingStatus", "BOUND").put("datasetVersion", "TEST_ONLY");
        evidence.putArray("referenceKeys").add(reference);
        bound.put("inputHash", contract.inputHash(bound));
        var result = mapper.createObjectNode().put("status", "SUCCEEDED")
                .put("changesRecommendation", false).putNull("reasonCode");
        for (String field : new String[]{"contractVersion", "requestId", "inputHash", "purpose"}) result.set(field, bound.path(field));
        var content = result.putObject("result").put("targetName", bound.path("input").path("targetName").asText());
        var signal = bound.path("input").path("signals").get(0);
        content.putArray("connections").addObject().put("questionId", signal.path("questionId").asText())
                .put("connection", "글쓰기 수업을 탐색해 볼 수 있어요.").putArray("referenceKeys").add(reference);
        var envelope = mapper.createObjectNode().put("transportVersion", "EXPLORATION_MODEL_EXECUTION_V1_0")
                .put("mode", "MODEL").put("generationAuthorized", true).put("mutationKey", mutation)
                .put("originalInputHash", original.path("inputHash").asText())
                .put("sourceContextHash", original.path("sourceContextHash").asText()).put("jobId", 1)
                .put("bindingHash", "b".repeat(64));
        envelope.set("boundRequest", bound);
        envelope.set("response", result);
        envelope.putObject("providerMetadata").put("provider", "openai").put("model", "TEST_ONLY")
                .put("promptVersion", ExplorationAiContract.promptVersion(original));
        return envelope;
    }

    private String endpoint(long result, String major) {
        return "/api/exploration/v1/results/" + result + "/majors/" + major + "/explanation";
    }
}
