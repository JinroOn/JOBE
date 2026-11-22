package com.jinroon.jobe.domain.exploration;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.jinroon.jobe.domain.exploration.ai.*;
import com.jinroon.jobe.domain.exploration.context.ResultContextService;
import com.jinroon.jobe.domain.exploration.support.PostResultSupportService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ExplorationAiInputIntegrationTest extends CompletedExplorationTestSupport {
    @Autowired ResultContextService contexts;
    @Autowired PostResultSupportService support;
    @Autowired ExplorationAiInputService inputs;
    @Autowired ExplorationAiContract contract;
    @Autowired ExplorationAiResponseAdapter outputs;
    @Test void authenticPreparedContextProducesMinimalUnboundInputWithoutJobsOrLegacyAiData()throws Exception {
        long unrelated=unrelatedLegacyPlan();var existingPlan=jdbc.queryForMap("SELECT * FROM major_weekly_plans WHERE id=?",unrelated);
        long rid=completedResult(true);long id=contexts.prepare(OWNER,rid,"context","CONSULTATION",0,null).body().path("contextId").asLong();
        int requests=count("exploration_idempotency","user_id=?",OWNER);var prepared=inputs.prepare(OWNER,id,"CONSULTATION");JsonNode request=prepared.request();
        assertThat(prepared.readyForGeneration()).isFalse();assertThat(prepared.blockedReason()).isEqualTo("APPROVED_EVIDENCE_NOT_BOUND");contract.request(request);
        assertThat(request.toString()).doesNotContain("userId","resultId","sessionId","contextId","issuedId","answerText","optionCode","weakness","competency","fitScore","nickname","email","source_file","caution");
        assertThat(request.path("input").toString()).doesNotContain("requestId","sourceContextHash","inputHash","versions");
        assertThat(request.path("evidence").path("approvedOnly").asBoolean()).isTrue();assertThat(request.path("evidence").path("datasetVersion").isNull()).isTrue();
        assertThat(outputs.adapt(request,inputs.insufficientEvidence(request)).status()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(count("exploration_idempotency","user_id=?",OWNER)).isEqualTo(requests);assertThat(count("consultation_sessions","user_id=?",OWNER)).isZero();assertThat(count("major_weekly_plans","diagnosis_result_id IN (SELECT id FROM diagnosis_results WHERE user_id=?)",OWNER)).isZero();
        assertThat(jdbc.queryForMap("SELECT * FROM major_weekly_plans WHERE id=?",unrelated)).isEqualTo(existingPlan);
    }
    @Test void planKeepsTargetBeyondSevenAndOnlyApplicableSemanticPreferences()throws Exception {
        long rid=completedResult(true);String target=resultSnapshot(rid).path("rankedCandidates").get(7).path("majorCode").asText();JsonNode state=support.select(OWNER,rid,"support",0,"BOTH",target).body();
        JsonNode q=state.path("issued").get(4).path("question");support.answer(OWNER,rid,q.path("issuedId").asLong(),"answer",1,q.path("options").get(0).path("code").asText(),"m");
        long id=contexts.prepare(OWNER,rid,"context","PLAN",2,target).body().path("contextId").asLong();JsonNode request=inputs.prepare(OWNER,id,"PLAN").request();
        contract.request(request);JsonNode stored=mapper.readTree(jdbc.queryForObject("SELECT snapshot_json FROM exploration_result_context WHERE id=?",String.class,id));
        assertThat(request.path("input").path("targetName").asText()).isEqualTo(stored.path("targetMajor").path("major_name").asText());assertThat(request.path("input").path("candidateNames").size()).isLessThanOrEqualTo(8);
        assertThat(request.path("input").path("preferences").get(0).path("topic").asText()).isEqualTo("ACTIVITY-REPORT-01");assertThat(request.path("input").path("preferences").get(0).path("meaning").asText()).isEqualTo("STRONG_POSITIVE");
    }
    @Test void noCandidateConsultationStaysEmptyAndCannotBeReadAsPlan()throws Exception {
        long rid=completedResult(false);long id=contexts.prepare(OWNER,rid,"context","CONSULTATION",0,null).body().path("contextId").asLong();JsonNode request=inputs.prepare(OWNER,id,"CONSULTATION").request();
        assertThat(request.path("input").path("candidateNames").isEmpty()).isTrue();assertThat(request.path("input").path("targetName").isNull()).isTrue();assertThatThrownBy(()->inputs.prepare(OWNER,id,"PLAN")).isInstanceOf(com.jinroon.jobe.domain.exploration.api.ExplorationApiException.class);
    }
    @Test void laterSupportChangesDoNotChangeInputHashButEachPreparationGetsANewRequestId()throws Exception {
        long rid=completedResult(true);long id=contexts.prepare(OWNER,rid,"context","CONSULTATION",0,null).body().path("contextId").asLong();JsonNode first=inputs.prepare(OWNER,id,"CONSULTATION").request();
        support.select(OWNER,rid,"later",0,"ACTIVITY",null);JsonNode second=inputs.prepare(OWNER,id,"CONSULTATION").request();
        assertThat(second.path("inputHash")).isEqualTo(first.path("inputHash"));assertThat(second.path("requestId")).isNotEqualTo(first.path("requestId"));assertThat(second.path("input")).isEqualTo(first.path("input"));
    }
    @Test void ownershipCorruptionAndWithdrawalStopAdapterBeforeAnyGeneration()throws Exception {
        long rid=completedResult(true);long id=contexts.prepare(OWNER,rid,"context","CONSULTATION",0,null).body().path("contextId").asLong();
        assertThatThrownBy(()->inputs.prepare(OTHER,id,"CONSULTATION")).isInstanceOf(com.jinroon.jobe.domain.exploration.api.ExplorationApiException.class);
        jdbc.update("UPDATE exploration_result_context SET snapshot_json=JSON_SET(snapshot_json,'$.contextType','PLAN') WHERE id=?",id);assertThatThrownBy(()->inputs.prepare(OWNER,id,"CONSULTATION")).isInstanceOf(com.jinroon.jobe.domain.exploration.api.ExplorationApiException.class);
        deletion.delete(OWNER);assertThatThrownBy(()->inputs.prepare(OWNER,id,"CONSULTATION")).isInstanceOf(com.jinroon.jobe.global.exception.CustomException.class);
    }
}
