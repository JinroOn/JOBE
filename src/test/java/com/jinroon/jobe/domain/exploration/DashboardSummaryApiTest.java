package com.jinroon.jobe.domain.exploration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.diagnosis.entity.DiagnosisSession;
import com.jinroon.jobe.domain.diagnosis.repository.DiagnosisSessionRepository;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import com.jinroon.jobe.domain.exploration.service.ExplorationResultAssembler;
import com.jinroon.jobe.domain.exploration.service.ExplorationResultStore;
import com.jinroon.jobe.domain.result.entity.DiagnosisResult;
import com.jinroon.jobe.domain.result.repository.DiagnosisResultRepository;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 대시보드 요약(3.2.2 결과 이력·대시보드의 최신 결과·진행 회차 선택 정책).
 * OWNER·OTHER는 탐색형 DEV 허용 사용자이고 OUTSIDE는 허용 목록 밖이다.
 * 실제 MySQL을 쓰며, 공통 지원 클래스가 테스트 사용자와 그 데이터를 탈퇴 처리로 지운다.
 */
class DashboardSummaryApiTest extends ExplorationApiTestSupport {

    private static final String SUMMARY = "/api/dashboard/summary";
    private static final LocalDateTime BASE = LocalDateTime.of(2026, 11, 15, 12, 0, 0);

    @Autowired DiagnosisSessionRepository legacySessions;
    @Autowired DiagnosisResultRepository legacyResults;
    @Autowired ExplorationResultStore resultStore;
    @Autowired PlatformTransactionManager txManager;

    @Test
    void anonymousRequestIsRejected() throws Exception {
        mvc.perform(get(SUMMARY)).andExpect(status().isUnauthorized());
    }

    @Test
    void emptyUserGetsEmptySummary() throws Exception {
        JsonNode summary = summary(owner);
        assertThat(summary.path("latestResult").isNull()).isTrue();
        assertThat(summary.path("latestByType").path("LEGACY").isNull()).isTrue();
        assertThat(summary.path("latestByType").path("EXPLORATION_V1").isNull()).isTrue();
        assertThat(summary.path("inProgress").isEmpty()).isTrue();
        assertThat(summary.path("resume").isNull()).isTrue();
        assertThat(summary.path("exploration").asText()).isEqualTo("AVAILABLE");
    }

    @Test
    void latestCompletedResultIsChosenAcrossTypes() throws Exception {
        long legacySession = legacy(OWNER, "completed", BASE.minusDays(1));
        long legacyResult = legacyResult(legacySession, OWNER);
        long explorationResult = completedExploration(BASE);

        JsonNode summary = summary(owner);
        assertThat(summary.path("latestResult").path("type").asText()).isEqualTo("EXPLORATION_V1");
        assertThat(summary.path("latestResult").path("resultId").asLong()).isEqualTo(explorationResult);
        assertThat(summary.path("latestResult").path("resultStatus").asText()).isEqualTo("NO_SUPPORTED_CANDIDATE");
        assertThat(summary.path("latestResult").path("path").asText()).isEqualTo("/api/exploration/v1/results/" + explorationResult);
        assertThat(summary.path("latestByType").path("LEGACY").path("resultId").asLong()).isEqualTo(legacyResult);
        assertThat(summary.path("latestByType").path("LEGACY").path("path").asText()).isEqualTo("/api/results/" + legacyResult);

        // 레거시가 더 늦게 완료되면 레거시가 최신이다.
        long newerLegacy = legacyResult(legacy(OWNER, "completed", BASE.plusDays(1)), OWNER);
        JsonNode after = summary(owner);
        assertThat(after.path("latestResult").path("type").asText()).isEqualTo("LEGACY");
        assertThat(after.path("latestResult").path("resultId").asLong()).isEqualTo(newerLegacy);
        assertThat(after.path("latestByType").path("EXPLORATION_V1").path("resultId").asLong()).isEqualTo(explorationResult);
    }

    @Test
    void corruptExplorationResultIsSkippedInsteadOfFailingTheDashboard() throws Exception {
        long older = completedExploration(BASE.minusDays(1));
        long newer = completedExploration(BASE);
        jdbc.update("UPDATE exploration_result SET result_json=JSON_SET(result_json,'$.status','READY') WHERE id=?", newer);

        JsonNode summary = summary(owner);
        assertThat(summary.path("latestByType").path("EXPLORATION_V1").path("resultId").asLong()).isEqualTo(older);
        assertThat(summary.path("latestResult").path("resultId").asLong()).isEqualTo(older);
    }

    @Test
    void inProgressSessionsAreListedAndMostRecentlyStartedIsResumed() throws Exception {
        long legacySession = legacy(OWNER, "in_progress", BASE.minusHours(1));
        long explorationSession = start("dashboard-progress").path("sessionId").asLong();
        jdbc.update("UPDATE exploration_session SET started_at=? WHERE id=?", BASE, explorationSession);

        JsonNode summary = summary(owner);
        assertThat(summary.path("inProgress").size()).isEqualTo(2);
        assertThat(summary.path("resume").path("type").asText()).isEqualTo("EXPLORATION_V1");
        assertThat(summary.path("resume").path("sessionId").asLong()).isEqualTo(explorationSession);
        assertThat(summary.path("resume").path("path").asText()).isEqualTo("/api/exploration/v1/sessions/" + explorationSession);
        assertThat(summary.path("inProgress").get(1).path("sessionId").asLong()).isEqualTo(legacySession);
        assertThat(summary.path("inProgress").get(1).path("path").asText()).isEqualTo("/api/diagnoses/sessions/" + legacySession);
    }

    @Test
    void userOutsideExplorationScopeStillGetsLegacySummary() throws Exception {
        long legacyResult = legacyResult(legacy(OUTSIDE, "completed", BASE), OUTSIDE);
        legacy(OUTSIDE, "in_progress", BASE.plusHours(1));

        JsonNode summary = summary(outside);
        assertThat(summary.path("exploration").asText()).isEqualTo("UNAVAILABLE");
        assertThat(summary.path("latestResult").path("resultId").asLong()).isEqualTo(legacyResult);
        assertThat(summary.path("latestByType").path("EXPLORATION_V1").isNull()).isTrue();
        assertThat(summary.path("inProgress").size()).isEqualTo(1);
        assertThat(summary.path("resume").path("type").asText()).isEqualTo("LEGACY");
    }

    @Test
    void otherUsersDataIsNeverIncluded() throws Exception {
        completedExploration(BASE);
        legacyResult(legacy(OWNER, "completed", BASE), OWNER);
        legacy(OWNER, "in_progress", BASE);

        JsonNode summary = summary(other);
        assertThat(summary.path("latestResult").isNull()).isTrue();
        assertThat(summary.path("inProgress").isEmpty()).isTrue();
    }

    private JsonNode summary(String token) throws Exception {
        return response(get(SUMMARY).header("Authorization", token), 200);
    }

    private long legacy(long owner, String status, LocalDateTime at) {
        long id = legacySessions.saveAndFlush(DiagnosisSession.start(owner, 2, "{}")).getId();
        jdbc.update("UPDATE diagnosis_sessions SET status=?,started_at=?,completed_at=? WHERE id=?",
                status, at, status.equals("completed") ? at : null, id);
        return id;
    }

    private long legacyResult(long session, long owner) {
        return legacyResults.saveAndFlush(DiagnosisResult.create(session, owner, "{}", "{}", "[]",
                UUID.randomUUID().toString())).getId();
    }

    /** 봉인된 최소 결과. 완료 계산 자체는 별도 완료 API 테스트가 검증한다. */
    private long completedExploration(LocalDateTime completedAt) throws Exception {
        long session = start("dashboard-" + UUID.randomUUID()).path("sessionId").asLong();
        ObjectNode payload = mapper.createObjectNode();
        payload.put("sessionId", session);
        payload.put("resultContractVersion", ExplorationResultAssembler.CONTRACT);
        payload.put("sessionStatus", "completed");
        payload.put("status", "NO_SUPPORTED_CANDIDATE");
        payload.put("completionReason", "NORMAL");
        ObjectNode input = payload.putObject("input");
        input.put("synthetic", true);
        payload.put("inputHash", new ExplorationJson(mapper).hex(input));
        payload.putArray("groupSummaries");
        payload.putArray("majorSummaries");
        var tx = new TransactionTemplate(txManager);
        return Objects.requireNonNull(tx.execute(status -> {
            long id = resultStore.save(session, payload).getId();
            jdbc.update("UPDATE exploration_session SET status='completed',completed_at=? WHERE id=?", completedAt, session);
            return id;
        }));
    }
}
