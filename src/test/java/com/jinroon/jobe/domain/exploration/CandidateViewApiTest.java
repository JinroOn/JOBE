package com.jinroon.jobe.domain.exploration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CandidateViewApiTest extends CompletedExplorationTestSupport {
    @Autowired ExplorationJson json;
    @Autowired ExplorationStoredJson storedJson;
    @Autowired com.jinroon.jobe.domain.exploration.sharing.SharedResultProjector shares;

    @Test void allFinalCandidatesAndEquivalentGroupsAreVisibleWhileFirstScreenIsUnchanged()throws Exception{
        long id=completedResult(Map.of("INT_HUM",100));JsonNode stored=resultSnapshot(id);JsonNode response=view(id,owner,200);
        assertThat(response.path("source").asText()).isEqualTo("COMPLETION_SNAPSHOT");
        assertThat(response.path("items").findValuesAsText("majorCode")).isEqualTo(stored.path("rankedCandidates").findValuesAsText("majorCode"));
        assertThat(response.path("items").findValuesAsText("majorCode")).contains("MAJOR_ROW_4","MAJOR_ROW_5","MAJOR_ROW_8","MAJOR_ROW_9");
        assertThat(response.path("additionalCount").asInt()).isPositive();
        for(String code:List.of("MAJOR_ROW_4","MAJOR_ROW_5","MAJOR_ROW_8","MAJOR_ROW_9")) assertThat(card(response,code).path("isFirstScreen").asBoolean()).isFalse();
        assertThat(card(response,"MAJOR_ROW_4").path("basisGroupId")).isEqualTo(card(response,"MAJOR_ROW_11").path("basisGroupId"));
        assertThat(card(response,"MAJOR_ROW_9").path("basisGroupId")).isEqualTo(card(response,"MAJOR_ROW_3").path("basisGroupId"));
        assertThat(card(response,"MAJOR_ROW_9").path("basisGroupId")).isNotEqualTo(card(response,"MAJOR_ROW_4").path("basisGroupId"));
        JsonNode detail=result(id);assertThat(detail.path("majors").findValuesAsText("majorCode")).isEqualTo(stored.path("firstScreen").findValuesAsText("majorCode"));
        assertThat(detail.path("candidateBrowsing").path("totalCount")).isEqualTo(response.path("totalCount"));
        assertThat(response.toString()).doesNotContain("basisInputs","sourceHashes","source_file","\"total\"","\"score\"","majorId","inputHash","usedInterestAxes","collapsed");
        mvc.perform(get(path(id)).header("Authorization",owner)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","private, no-store"));
    }
    @Test void repeatedReadsArePureAndDoNotChangeResultSealRevisionOrCreateJobs()throws Exception{
        long id=completedResult(true);long session=resultSnapshot(id).path("sessionId").asLong();
        String raw=raw(id),seal=seal(id);long revision=sessionRevision(session);
        int requests=count("exploration_idempotency","user_id=?",OWNER),contexts=count("exploration_result_context","result_id=?",id);
        JsonNode first=view(id,owner,200);assertThat(view(id,owner,200)).isEqualTo(first);
        assertThat(raw(id)).isEqualTo(raw);assertThat(seal(id)).isEqualTo(seal);assertThat(sessionRevision(session)).isEqualTo(revision);
        assertThat(count("exploration_idempotency","user_id=?",OWNER)).isEqualTo(requests);assertThat(count("exploration_result_context","result_id=?",id)).isEqualTo(contexts);
    }
    @Test void newlySealedViewDoesNotDependOnAlteredOrMissingRuntimePins()throws Exception{
        long id=completedResult(true),session=resultSnapshot(id).path("sessionId").asLong();JsonNode before=view(id,owner,200);
        try(var restored=preserveRuntimeSnapshot(session)){
            jdbc.update("UPDATE exploration_runtime_snapshot s JOIN exploration_session_pin p ON p.snapshot_hash=s.content_hash SET s.payload_json=JSON_OBJECT('changed',true) WHERE p.session_id=?",session);
            assertThat(view(id,owner,200)).isEqualTo(before);assertThat(result(id).path("candidateBrowsing").path("totalCount")).isEqualTo(before.path("totalCount"));
        }
        jdbc.update("DELETE FROM exploration_session_pin WHERE session_id=?",session);
        assertThat(view(id,owner,200)).isEqualTo(before);
    }
    @Test void historicalProjectionKeepsOldBytesSealAndFirstScreenAndRequiresValidPinnedSources()throws Exception{
        long id=completedResult(true);ObjectNode old=resultSnapshot(id).deepCopy();old.remove("candidateView");reseal(id,old);
        String raw=raw(id),seal=seal(id);JsonNode response=view(id,owner,200);
        assertThat(response.path("source").asText()).isEqualTo("SEALED_RESULT_PROJECTION");
        assertThat(response.path("items").findValuesAsText("majorCode")).isEqualTo(old.path("rankedCandidates").findValuesAsText("majorCode"));
        assertThat(raw(id)).isEqualTo(raw);assertThat(seal(id)).isEqualTo(seal);
        long session=old.path("sessionId").asLong();
        try(var restored=preserveRuntimeSnapshot(session)){
            jdbc.update("UPDATE exploration_runtime_snapshot s JOIN exploration_session_pin p ON p.snapshot_hash=s.content_hash SET s.payload_json=JSON_OBJECT('changed',true) WHERE p.session_id=?",session);
            assertThat(view(id,owner,503).path("code").asText()).isEqualTo("CATALOG_INTEGRITY_ERROR");result(id);
        }
        jdbc.update("DELETE FROM exploration_session_pin WHERE session_id=?",session);view(id,owner,503);result(id);
    }
    @Test void noCandidateResultIsEmptyAndDoesNotRequireHistoricalCatalog()throws Exception{
        long id=completedResult(false);JsonNode before=view(id,owner,200);assertThat(before.path("totalCount").asInt()).isZero();assertThat(before.path("items")).isEmpty();
        ObjectNode old=resultSnapshot(id).deepCopy();old.remove("candidateView");reseal(id,old);jdbc.update("DELETE FROM exploration_session_pin WHERE session_id=?",old.path("sessionId").asLong());
        JsonNode response=view(id,owner,200);assertThat(response.path("status").asText()).isEqualTo("NO_SUPPORTED_CANDIDATE");assertThat(response.path("basisGroups")).isEmpty();
    }
    @Test void ownershipAuthenticationAndQueryErrorsKeepExistingContracts()throws Exception{
        long id=completedResult(true);
        mvc.perform(get(path(id))).andExpect(status().isUnauthorized());
        view(id,other,404);view(Long.MAX_VALUE,owner,404);
        response(get(path(id)+"?sort=score").header("Authorization",owner),400);
        response(get("/api/exploration/v1/results/not-a-number/candidates").header("Authorization",owner),400);
        deletion.delete(OWNER);view(id,owner,401);
    }
    @Test void damagedResultAndPresentMalformedViewNeverFallBackToHistoricalProjection()throws Exception{
        long id=completedResult(true);ObjectNode payload=resultSnapshot(id).deepCopy();
        jdbc.update("UPDATE exploration_result SET result_json=JSON_SET(result_json,'$.candidateView.viewVersion','FUTURE') WHERE id=?",id);
        view(id,owner,503);
        ((ObjectNode)payload.path("candidateView")).put("viewVersion","FUTURE");reseal(id,payload);view(id,owner,503);
    }
    @Test void duplicateCandidatePrivateFieldsAndWrongGroupMembersFailEvenWithASyntheticSeal()throws Exception{
        long id=completedResult(true);ObjectNode original=resultSnapshot(id).deepCopy();ObjectNode changed=original.deepCopy();
        String code=changed.path("rankedCandidates").get(0).path("majorCode").asText();
        ((ObjectNode)changed.path("candidateView").path("cards").path(code)).put("personalAnswer","forged");reseal(id,changed);view(id,owner,503);
        changed=original.deepCopy();((com.fasterxml.jackson.databind.node.ArrayNode)changed.path("rankedCandidates")).add(changed.path("rankedCandidates").get(0));reseal(id,changed);view(id,owner,503);
    }
    @Test void supportTargetSelectionAndNewDiagnosisDoNotChangeHistoricalCandidateView()throws Exception{
        long id=completedResult(Map.of("INT_HUM",100));JsonNode before=view(id,owner,200);String raw=raw(id),seal=seal(id);
        response(post("/api/exploration/v1/results/"+id+"/support").header("Authorization",owner).header("Idempotency-Key","select-extra")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json.write(Map.of("expectedSupportRevision",0,"purpose","PLAN","targetMajorCode","MAJOR_ROW_4"))),200);
        response(post("/api/exploration/v1/results/"+id+"/support").header("Authorization",owner).header("Idempotency-Key","change-extra")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(json.write(Map.of("expectedSupportRevision",1,"purpose","BOTH","targetMajorCode","MAJOR_ROW_9"))),200);
        completedResult(false);
        assertThat(view(id,owner,200)).isEqualTo(before);assertThat(raw(id)).isEqualTo(raw);assertThat(seal(id)).isEqualTo(seal);
    }
    @Test void sharedSummaryNeverExpandsToAllCandidatesOrPrivateBasisInputs()throws Exception{
        long id=completedResult(Map.of("INT_HUM",100));JsonNode snapshot=resultSnapshot(id);
        JsonNode shared=shares.project(snapshot,null);
        assertThat(shares.allowed(shared)).isTrue();assertThat(shared.path("majors").size()).isLessThanOrEqualTo(7);
        assertThat(shared.toString()).doesNotContain("basisInputs","basisGroups","candidateBrowsing","evidenceLevel","candidateView");
    }
    private JsonNode result(long id)throws Exception{return response(get("/api/exploration/v1/results/"+id).header("Authorization",owner),200);}
    private String path(long id){return "/api/exploration/v1/results/"+id+"/candidates";}
    private JsonNode view(long id,String token,int status)throws Exception{return response(get(path(id)).header("Authorization",token),status);}
    private JsonNode card(JsonNode view,String code){for(JsonNode c:view.path("items"))if(code.equals(c.path("majorCode").asText()))return c;throw new AssertionError(code);}
    private String raw(long id){return jdbc.queryForObject("SELECT result_json FROM exploration_result WHERE id=?",String.class,id);}
    private String seal(long id){return jdbc.queryForObject("SELECT content_hash FROM exploration_result_seal WHERE result_id=?",String.class,id);}
    /** Synthetic historical/damage fixture only. Application read paths must never do this. */
    private void reseal(long id,ObjectNode payload){var frozen=storedJson.freeze(payload);jdbc.update("UPDATE exploration_result SET result_json=? WHERE id=?",frozen.encoded(),id);jdbc.update("UPDATE exploration_result_seal SET content_hash=? WHERE result_id=?",frozen.hash(),id);}
}
