package com.jinroon.jobe.domain.exploration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.jinroon.jobe.domain.exploration.service.ExplorationRuntimeCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ExplorationComparisonApiTest extends CompletedExplorationTestSupport {
    @Autowired ExplorationRuntimeCatalog runtime;
    @Test void overviewListsOnlyFrozenCandidatesAndPairPreservesChosenSideOrder()throws Exception {
        long rid=completedResult(true);JsonNode snapshot=resultSnapshot(rid),overview=overview(rid,owner,200);
        assertThat(overview.path("candidateChoices").size()).isEqualTo(snapshot.path("rankedCandidates").size());
        String a=code(rid,0),b=code(rid,1);JsonNode pair=pair(rid,a,b,200);
        assertThat(pair.path("left").path("majorCode").asText()).isEqualTo(a);assertThat(pair.path("right").path("majorCode").asText()).isEqualTo(b);
        JsonNode reversed=pair(rid,b,a,200);assertThat(reversed.path("left")).isEqualTo(pair.path("right"));
        assertThat(reversed.path("dimensions").path("activityAxes").path("leftOnly")).isEqualTo(pair.path("dimensions").path("activityAxes").path("rightOnly"));
    }
    @Test void resultCandidateBeyondFirstSevenCanBeCompared()throws Exception {
        long rid=completedResult(true);assertThat(resultSnapshot(rid).path("rankedCandidates").size()).isGreaterThan(7);
        assertThat(pair(rid,code(rid,0),code(rid,7),200).path("right").path("majorCode").asText()).isEqualTo(code(rid,7));
    }
    @Test void noCandidateDisablesComparisonAndRejectsPair()throws Exception {
        long rid=completedResult(false);JsonNode overview=overview(rid,owner,200);
        assertThat(overview.path("available").asBoolean()).isFalse();assertThat(overview.path("candidateChoices").isEmpty()).isTrue();
        assertThat(pair(rid,"MAJOR_ROW_2","MAJOR_ROW_3",409).path("code").asText()).isEqualTo("COMPARISON_TARGET_UNAVAILABLE");
        JsonNode result=response(get("/api/exploration/v1/results/"+rid).header("Authorization",owner),200);
        assertThat(result.path("capabilities").path("comparisonAvailable").asBoolean()).isFalse();
    }
    @Test void duplicateOrOutsideCandidateCannotBeSubstituted()throws Exception {
        long rid=completedResult(true);String a=code(rid,0);
        assertThat(pair(rid,a,a,400).path("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(pair(rid,a,"MAJOR_ROW_NOT_IN_RESULT",422).path("code").asText()).isEqualTo("MAJOR_NOT_IN_RESULT");
    }
    @Test void sameHistoryMembershipIsSkippedButDistinctDirectionsHaveFrozenDescriptions()throws Exception {
        long rid=completedResult(Map.of("INT_HUM",100));
        JsonNode same=pair(rid,"MAJOR_ROW_3","MAJOR_ROW_9",200);
        assertThat(same.path("comparisonState").asText()).isEqualTo("SAME_DIRECTIONS");
        assertThat(same.path("skippedComparisonSets").get(0).path("declaredSkipPair").asBoolean()).isTrue();
        JsonNode distinct=pair(rid,"MAJOR_ROW_2","MAJOR_ROW_3",200);
        assertThat(distinct.path("comparisonState").asText()).isEqualTo("DISTINCT_DIRECTIONS");
        JsonNode panel=distinct.path("comparisonSets").get(0);assertThat(panel.path("comparisonSet").asText()).isEqualTo("CMP-HISTORY");
        assertThat(panel.path("leftDirections").get(0).path("directionCode").asText()).isEqualTo("ARCHAEOLOGY_FIELD");
        assertThat(panel.path("leftDirections").get(0).path("descriptions").size()).isEqualTo(3);
    }
    @Test void comparisonUsesSealedAnswersEvenWhenLiveRevisionsChangeLater()throws Exception {
        long rid=completedResult(Map.of("INT_HUM",100));JsonNode before=pair(rid,"MAJOR_ROW_2","MAJOR_ROW_3",200);
        assertThat(before.path("comparisonSets").get(0).path("focusResponses").size()).isPositive();
        long sid=resultSnapshot(rid).path("sessionId").asLong();
        jdbc.update("UPDATE exploration_answer_revision a JOIN exploration_issued_question q ON q.id=a.issued_id SET a.option_code='CMP_HISTORY__UNSURE' WHERE q.session_id=? AND q.comparison_set='CMP-HISTORY'",sid);
        assertThat(pair(rid,"MAJOR_ROW_2","MAJOR_ROW_3",200)).isEqualTo(before);
    }
    @Test void noWinnerScoresRawInputsOrUnsupportedFactsAreReturned()throws Exception {
        long rid=completedResult(true);JsonNode pair=pair(rid,code(rid,0),code(rid,1),200);
        assertThat(pair.path("scoreEffect").asInt()).isZero();assertThat(pair.path("changesRecommendation").asBoolean()).isFalse();
        assertThat(pair.path("left").path("careerInformationStatus").asText()).isEqualTo("NOT_PROVIDED");
        assertThat(pair.toString()).doesNotContain("winner","fitScore","finalScore","req","tend","snapshot_json","optionCode","userId");
        assertThat(pair.path("testOnly").asBoolean()).isTrue();assertThat(pair.path("left").path("domainReviewStatus").asText()).isEqualTo("PENDING");
    }
    @Test void getDoesNotWriteSessionResultSupportOrIdempotencyData()throws Exception {
        long rid=completedResult(true),sid=resultSnapshot(rid).path("sessionId").asLong(),revision=sessionRevision(sid);
        String stored=jdbc.queryForObject("SELECT result_json FROM exploration_result WHERE id=?",String.class,rid);int questions=count("exploration_issued_question","session_id=?",sid),requests=count("exploration_idempotency","user_id=?",OWNER);
        overview(rid,owner,200);pair(rid,code(rid,0),code(rid,1),200);
        assertThat(jdbc.queryForObject("SELECT result_json FROM exploration_result WHERE id=?",String.class,rid)).isEqualTo(stored);
        assertThat(sessionRevision(sid)).isEqualTo(revision);assertThat(count("exploration_issued_question","session_id=?",sid)).isEqualTo(questions);
        assertThat(count("exploration_idempotency","user_id=?",OWNER)).isEqualTo(requests);assertThat(count("exploration_post_result","result_id=?",rid)).isZero();
    }
    @Test void resultAndPinnedBundleTamperingFailClosed()throws Exception {
        long rid=completedResult(true);long sid=resultSnapshot(rid).path("sessionId").asLong();
        jdbc.update("UPDATE exploration_runtime_snapshot s JOIN exploration_session_pin p ON p.snapshot_hash=s.content_hash SET s.payload_json=JSON_SET(s.payload_json,'$.mapping.majors[0].major_name','tampered') WHERE p.session_id=?",sid);
        assertThat(overview(rid,owner,503).path("code").asText()).isEqualTo("CATALOG_INTEGRITY_ERROR");
        jdbc.update("UPDATE exploration_result SET result_json=JSON_SET(result_json,'$.status','READY') WHERE id=?",rid);
        overview(rid,owner,503);
    }
    @Test void strictQueryAndOwnershipAreEnforced()throws Exception {
        long rid=completedResult(true);String a=code(rid,0),b=code(rid,1);
        for(String query:List.of("userId=1","leftMajorCode="+a,"leftMajorCode="+a+"&rightMajorCode="+b+"&runMode=PUBLIC","leftMajorCode="+a+"&leftMajorCode="+a+"&rightMajorCode="+b,"leftMajorCode=&rightMajorCode="+b))response(get(path(rid)+"?"+query).header("Authorization",owner),400);
        overview(rid,other,404);overview(Long.MAX_VALUE,owner,404);response(get(path(rid)),401);
    }
    @Test void notIssuedOptionsRemainEmptyAndDoNotConsumeBudget()throws Exception {
        long sid=start("new").path("sessionId").asLong(),revision=sessionRevision(sid);
        JsonNode options=options(sid,"CMP-HISTORY",owner,200);
        assertThat(options.path("availability").asText()).isEqualTo("NOT_ISSUED");assertThat(options.path("issuedQuestions").isEmpty()).isTrue();
        assertThat(count("exploration_issued_question","session_id=?",sid)).isZero();assertThat(sessionRevision(sid)).isEqualTo(revision);
    }
    @Test void issuedOptionsUseActualSnapshotsIncludeUnsureAndRetainExcludedHistory()throws Exception {
        long rid=completedResult(Map.of("INT_HUM",100)),sid=resultSnapshot(rid).path("sessionId").asLong();
        JsonNode options=options(sid,"CMP-HISTORY",owner,200);assertThat(options.path("issuedQuestions").size()).isPositive();
        JsonNode question=options.path("issuedQuestions").get(0);long iid=question.path("issuedId").asLong();
        assertThat(question.path("options").toString()).contains("CMP_HISTORY__UNSURE");
        jdbc.update("UPDATE exploration_issued_question SET effective=false WHERE id=?",iid);
        JsonNode excluded=options(sid,"CMP-HISTORY",owner,200).path("issuedQuestions").get(0);
        assertThat(excluded.path("effective").asBoolean()).isFalse();assertThat(excluded.path("options")).isEqualTo(question.path("options"));
        jdbc.update("UPDATE exploration_issued_question SET snapshot_json=JSON_SET(snapshot_json,'$.question.question_text','changed') WHERE id=?",iid);
        options(sid,"CMP-HISTORY",owner,503);
    }
    @Test void optionsRejectLegacyUnknownDuplicateAndForeignQueries()throws Exception {
        long sid=start("new").path("sessionId").asLong();
        options(sid,"CMP_HISTORY",owner,400);options(sid,"CMP-NOT-REAL",owner,422);options(sid,"CMP-HISTORY",other,404);
        response(get(optionsPath(sid)).header("Authorization",owner),400);
        response(get(optionsPath(sid)+"?comparisonSet=CMP-HISTORY&comparisonSet=CMP-HISTORY").header("Authorization",owner),400);
        response(get(optionsPath(sid)+"?comparisonSet=CMP-HISTORY&version=1").header("Authorization",owner),400);
    }
    @Test void oldResultUsesFrozenCatalogAfterLiveMetadataChangesAndDeletionBlocksAccess()throws Exception {
        long rid=completedResult(true);String a=code(rid,0),b=code(rid,1);JsonNode before=pair(rid,a,b,200);
        var row=jdbc.queryForMap("SELECT id,mapping_json FROM exploration_major_mapping WHERE major_code=? ORDER BY id DESC LIMIT 1",a);
        jdbc.update("UPDATE exploration_major_mapping SET mapping_json=JSON_SET(mapping_json,'$.major_name','changed live') WHERE id=?",row.get("id"));
        try{assertThat(pair(rid,a,b,200)).isEqualTo(before);}finally{jdbc.update("UPDATE exploration_major_mapping SET mapping_json=? WHERE id=?",row.get("mapping_json"),row.get("id"));}
        deletion.delete(OWNER);response(get(path(rid)).header("Authorization",owner),401);
    }
    private String path(long rid){return "/api/exploration/v1/results/"+rid+"/comparison";}
    private String optionsPath(long sid){return "/api/exploration/v1/sessions/"+sid+"/comparison-options";}
    private String code(long rid,int i)throws Exception{return resultSnapshot(rid).path("rankedCandidates").get(i).path("majorCode").asText();}
    private JsonNode overview(long rid,String token,int status)throws Exception{return response(get(path(rid)).header("Authorization",token),status);}
    private JsonNode pair(long rid,String a,String b,int status)throws Exception{return response(get(path(rid)).param("leftMajorCode",a).param("rightMajorCode",b).header("Authorization",owner),status);}
    private JsonNode options(long sid,String set,String token,int status)throws Exception{return response(get(optionsPath(sid)).param("comparisonSet",set).header("Authorization",token),status);}
}
