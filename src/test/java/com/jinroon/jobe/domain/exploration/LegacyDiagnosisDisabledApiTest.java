package com.jinroon.jobe.domain.exploration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.jinroon.jobe.domain.diagnosis.config.ExamQuestionSeedInitializer;
import com.jinroon.jobe.domain.diagnosis.entity.DiagnosisSession;
import com.jinroon.jobe.domain.diagnosis.repository.DiagnosisSessionRepository;
import com.jinroon.jobe.domain.result.entity.DiagnosisResult;
import com.jinroon.jobe.domain.result.repository.DiagnosisResultRepository;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 기본 설정에서는 기존 진단 API가 모두 막히고, 이력·대시보드는 탐색 진단만 다룬다. 데이터는 지우지 않는다. */
class LegacyDiagnosisDisabledApiTest extends ExplorationApiTestSupport {
    private static final String HISTORY = "/api/exploration/v1/history";
    private static final String CODE = "LEGACY_DIAGNOSIS_RETIRED";
    @Autowired DiagnosisSessionRepository legacySessions;
    @Autowired DiagnosisResultRepository legacyResults;
    @Autowired ApplicationContext context;

    @Test
    void everyLegacyApiIsGoneForSignedInUsers() throws Exception {
        long session = legacySession("completed");
        long result = legacyResult(session);
        for (String path : List.of("/api/diagnoses/questions", "/api/diagnoses/sessions/me", "/api/diagnoses/sessions/" + session,
                "/api/results", "/api/results/" + result, "/api/results/" + result + "/major-scores",
                "/api/plans/results/" + result, "/api/consultations/sessions/me")) {
            gone(get(path).header("Authorization", owner));
        }
        for (String path : List.of("/api/diagnoses/sessions", "/api/diagnoses/exam-answers", "/api/diagnoses/sessions/" + session + "/score",
                "/api/results/diagnosis-sessions/" + session + "/complete", "/api/results/" + result + "/ai-comment",
                "/api/plans", "/api/consultations/sessions")) {
            gone(post(path).header("Authorization", owner).contentType(MediaType.APPLICATION_JSON).content("{}"));
        }
        gone(patch("/api/diagnoses/sessions/" + session).header("Authorization", owner).contentType(MediaType.APPLICATION_JSON).content("{}"));
        gone(delete("/api/diagnoses/sessions/" + session).header("Authorization", owner));
        gone(get(URI.create("/api/%64iagnoses/questions")).header("Authorization", owner));
        // 막힌 요청은 저장된 데이터를 바꾸지 않는다.
        assertThat(legacySessions.existsById(session)).isTrue();
        assertThat(legacyResults.existsById(result)).isTrue();
    }

    @Test
    void sharedLegacyLinkIsGoneAndOtherLegacyApisStillRequireLogin() throws Exception {
        gone(get("/api/results/share/" + UUID.randomUUID()));
        response(get("/api/results"), 401);
        response(post("/api/plans").contentType(MediaType.APPLICATION_JSON).content("{}"), 401);
    }

    @Test
    void historyAndDashboardContainExplorationOnly() throws Exception {
        legacyResult(legacySession("completed"));
        legacySession("in_progress");
        long exploration = start("legacy-off").path("sessionId").asLong();

        for (String type : new String[]{null, "ALL", "EXPLORATION_V1"}) {
            MockHttpServletRequestBuilder request = get(HISTORY + "/sessions").header("Authorization", owner);
            if (type != null) request.param("type", type);
            JsonNode items = response(request, 200).path("items");
            assertThat(items.size()).isEqualTo(1);
            assertThat(items.get(0).path("sessionId").asLong()).isEqualTo(exploration);
            assertThat(items.get(0).path("type").asText()).isEqualTo("EXPLORATION_V1");
        }
        assertThat(response(get(HISTORY + "/results").header("Authorization", owner), 200).path("items").isEmpty()).isTrue();
        gone(get(HISTORY + "/sessions").param("type", "LEGACY").header("Authorization", owner));

        JsonNode summary = response(get("/api/dashboard/summary").header("Authorization", owner), 200);
        assertThat(summary.path("latestResult").isNull()).isTrue();
        assertThat(summary.path("latestByType").has("LEGACY")).isTrue();
        assertThat(summary.path("latestByType").path("LEGACY").isNull()).isTrue();
        assertThat(summary.path("inProgress").size()).isEqualTo(1);
        assertThat(summary.path("resume").path("type").asText()).isEqualTo("EXPLORATION_V1");
    }

    @Test
    void withdrawalStillRemovesStoredLegacyData() {
        long session = legacySession("completed");
        long result = legacyResult(session);
        deletion.delete(OWNER);
        assertThat(legacySessions.existsById(session)).isFalse();
        assertThat(legacyResults.existsById(result)).isFalse();
    }

    @Test
    void legacyQuestionsAreNotSeeded() {
        assertThat(context.getBeansOfType(ExamQuestionSeedInitializer.class)).isEmpty();
    }

    private void gone(MockHttpServletRequestBuilder request) throws Exception {
        assertThat(response(request, 410).path("code").asText()).isEqualTo(CODE);
    }

    private long legacySession(String status) {
        long id = legacySessions.saveAndFlush(DiagnosisSession.start(OWNER, 2, "{}")).getId();
        jdbc.update("UPDATE diagnosis_sessions SET status=? WHERE id=?", status, id);
        return id;
    }

    private long legacyResult(long session) {
        return legacyResults.saveAndFlush(DiagnosisResult.create(session, OWNER, "{}", "{}", "[]", UUID.randomUUID().toString())).getId();
    }
}
