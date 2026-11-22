package com.jinroon.jobe.domain.exploration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import com.jinroon.jobe.domain.exploration.support.PostResultSupportService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

class PostResultSupportApiTest extends CompletedExplorationTestSupport {
    @Autowired PostResultSupportService support;

    @Test void getBeforeSelectionIsReadOnlyAndBothIssuesExactlyFourPlusFour() throws Exception {
        long rid=completedResult(true);
        assertThat(getSupport(rid,owner,200).path("supportRevision").asLong()).isZero();
        assertThat(count("exploration_post_result","result_id=?",rid)).isZero();
        JsonNode selected=select(rid,0,"BOTH",null,"select",200);
        assertThat(selected.path("supportRevision").asLong()).isEqualTo(1);
        assertThat(selected.path("progress").path("issued").asInt()).isEqualTo(8);
        assertThat(selected.path("questionsOptional").asBoolean()).isTrue();
        Set<String> ids=new HashSet<>();for(JsonNode row:selected.path("issued")){ids.add(row.path("question").path("questionId").asText());assertThat(row.path("question").path("required").asBoolean()).isFalse();}
        assertThat(ids).containsExactlyInAnyOrder("READINESS-INFO-01","READINESS-CMP-01","READINESS-SELF-01","READINESS-ACT-02","ACTIVITY-REPORT-01","ACTIVITY-PROJECT-01","ACTIVITY-READ-01","ACTIVITY-EXPERIMENT-01");
        assertThat(selected.toString()).doesNotContain("numeric_value","meaning_code","scoring_role");
        assertThat(getSupport(rid,owner,200)).isEqualTo(selected);
    }

    @Test void activityWithoutCandidatesIsAllowedAndPlanBothAreRejected() throws Exception {
        long rid=completedResult(false);
        for(String purpose:List.of("PLAN","BOTH"))assertThat(select(rid,0,purpose,null,purpose,409).path("code").asText()).isEqualTo("PLAN_TARGET_UNAVAILABLE");
        assertThat(count("exploration_post_result","result_id=?",rid)).isZero();
        assertThat(count("exploration_idempotency","user_id=? AND operation='SUPPORT_SELECT'",OWNER)).isZero();
        JsonNode activity=select(rid,0,"ACTIVITY",null,"activity",200);
        assertThat(activity.path("progress").path("issued").asInt()).isEqualTo(6);
        assertThat(activity.path("activityAvailability").asText()).isEqualTo("NOT_EVALUATED");
        assertThat(activity.path("activitiesEndpoint").asText()).isEqualTo("/api/exploration/v1/results/"+rid+"/activities");
        assertThat(activity.path("planGenerationAvailable").asBoolean()).isFalse();
        assertThat(select(rid,1,"ACTIVITY","MAJOR_ROW_2","target",422).path("code").asText()).isEqualTo("TARGET_MAJOR_NOT_IN_RESULT");
    }

    @Test void targetMustBelongToAllFrozenResultCandidatesIncludingBeyondFirstSeven() throws Exception {
        long rid=completedResult(true);JsonNode stored=resultSnapshot(rid);
        Set<String> displayed=new HashSet<>();stored.path("majorSummaries").forEach(m->displayed.add(m.path("majorCode").asText()));
        String extra=null;for(JsonNode m:stored.path("rankedCandidates"))if(!displayed.contains(m.path("majorCode").asText())){extra=m.path("majorCode").asText();break;}
        assertThat(extra).isNotNull();
        assertThat(select(rid,0,"PLAN","NOT_A_RESULT_MAJOR","bad",422).path("code").asText()).isEqualTo("TARGET_MAJOR_NOT_IN_RESULT");
        assertThat(select(rid,0,"PLAN",extra,"valid",200).path("targetMajorCode").asText()).isEqualTo(extra);
    }

    @Test void purposeChangesPreserveEightQuestionBudgetAndRestoreSameIdsAndAnswers() throws Exception {
        long rid=completedResult(true);JsonNode plan=select(rid,0,"PLAN",null,"plan",200);JsonNode q=plan.path("issued").get(0).path("question");
        long iid=q.path("issuedId").asLong();String code=q.path("options").get(0).path("code").asText();
        answer(rid,iid,1,code,"answer","mutation",200);
        String snapshot=jdbc.queryForObject("SELECT snapshot_json FROM exploration_support_issued WHERE id=?",String.class,iid);
        JsonNode activity=select(rid,2,"ACTIVITY",null,"switch",200);
        assertThat(activity.path("progress").path("issued").asInt()).isEqualTo(8);assertThat(activity.path("progress").path("active").asInt()).isZero();
        assertThat(activity.path("budgetLimited").asBoolean()).isTrue();assertThat(activity.path("nextAction").asText()).isEqualTo("INFO_LIMIT_REACHED");
        assertThat(answer(rid,iid,3,code,"excluded","new",422).path("code").asText()).isEqualTo("QUESTION_NOT_EFFECTIVE");
        JsonNode restored=select(rid,3,"PLAN",null,"restore",200);
        assertThat(restored.path("progress").path("issued").asInt()).isEqualTo(8);
        assertThat(row(restored,iid).path("answerCode").asText()).isEqualTo(code);assertThat(row(restored,iid).path("effective").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT snapshot_json FROM exploration_support_issued WHERE id=?",String.class,iid)).isEqualTo(snapshot);
        assertThat(count("exploration_support_answer","issued_id=? AND is_current=true",iid)).isEqualTo(1);
    }

    @Test void activityToBothUsesOnlyRemainingSlotsAndReportsOmittedQuestions() throws Exception {
        long rid=completedResult(true);select(rid,0,"ACTIVITY",null,"activity",200);
        JsonNode both=select(rid,1,"BOTH",null,"both",200);
        assertThat(both.path("progress").path("issued").asInt()).isEqualTo(8);
        assertThat(both.path("budgetLimited").asBoolean()).isTrue();assertThat(both.path("omittedQuestionIds").size()).isEqualTo(2);
        assertThat(select(rid,2,"BOTH",null,"again",200).path("supportRevision").asLong()).isEqualTo(2);
    }

    @Test void selectionReplayStaleAndBodyConflictsDoNotDuplicateSupportOrExposures() throws Exception {
        long rid=completedResult(true);JsonNode original=select(rid,0,"BOTH",null,"same",200);
        assertThat(select(rid,0,"BOTH",null,"same",200)).isEqualTo(original);
        assertThat(select(rid,1,"PLAN",null,"same",409).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(select(rid,0,"PLAN",null,"stale",409).path("code").asText()).isEqualTo("STALE_REVISION");
        assertThat(count("exploration_post_result","result_id=?",rid)).isEqualTo(1);
        assertThat(count("exploration_support_issued","post_result_id IN (SELECT id FROM exploration_post_result WHERE result_id=?)",rid)).isEqualTo(8);
    }

    @Test void answerRevisionsAndMutationAliasesReplayWithoutLosingTheCurrentAnswer() throws Exception {
        long rid=completedResult(true);JsonNode selected=select(rid,0,"BOTH",null,"select",200);JsonNode q=selected.path("issued").get(0).path("question");
        long iid=q.path("issuedId").asLong();String code=q.path("options").get(0).path("code").asText();
        JsonNode first=answer(rid,iid,1,code,"first","mutation",200);
        answer(rid,iid,2,q.path("options").get(1).path("code").asText(),"edit","edit",200);
        assertThat(answer(rid,iid,1,code,"first","mutation",200)).isEqualTo(first);
        assertThat(answer(rid,iid,1,code,"alias","mutation",200)).isEqualTo(first);
        assertThat(answer(rid,iid,3,code,"conflict","mutation",409).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(answer(rid,iid,1,code,"stale","new",409).path("code").asText()).isEqualTo("STALE_REVISION");
        assertThat(count("exploration_support_answer","issued_id=?",iid)).isEqualTo(2);
        assertThat(count("exploration_support_answer","issued_id=? AND is_current=true",iid)).isEqualTo(1);
    }

    @Test void targetChangesKeepLatestHistoryButDoNotApplyOldReadinessToAnotherMajor() throws Exception {
        long rid=completedResult(true);JsonNode candidates=resultSnapshot(rid).path("rankedCandidates");String a=candidates.get(0).path("majorCode").asText(),b=candidates.get(1).path("majorCode").asText();
        JsonNode selected=select(rid,0,"PLAN",a,"select",200);JsonNode q=selected.path("issued").get(0).path("question");long iid=q.path("issuedId").asLong();String code=q.path("options").get(0).path("code").asText();
        answer(rid,iid,1,code,"answer","a",200);JsonNode changed=select(rid,2,"PLAN",b,"target",200);
        assertThat(row(changed,iid).path("answerCode").asText()).isEqualTo(code);assertThat(row(changed,iid).path("answerTargetMajorCode").asText()).isEqualTo(a);
        assertThat(row(changed,iid).path("answerMatchesTarget").asBoolean()).isFalse();assertThat(changed.path("progress").path("answered").asInt()).isZero();
        JsonNode updated=answer(rid,iid,3,code,"update","b",200);
        assertThat(row(updated,iid).path("answerMatchesTarget").asBoolean()).isTrue();assertThat(row(updated,iid).path("answerRevision").asInt()).isEqualTo(2);
    }

    @Test void invalidOptionAndOtherResultOrUserIssuedIdsAreRejected() throws Exception {
        long rid=completedResult(true);JsonNode selected=select(rid,0,"BOTH",null,"select",200);long iid=selected.path("issued").get(0).path("question").path("issuedId").asLong();
        assertThat(answer(rid,iid,1,"NOT_ISSUED","option","mutation",422).path("code").asText()).isEqualTo("OPTION_NOT_ISSUED");
        response(get(path(rid)).header("Authorization",other),404);
        response(post(path(rid)).header("Authorization",other).header("Idempotency-Key","foreign").contentType(MediaType.APPLICATION_JSON).content(selectionBody(0,"PLAN",null)),404);
        long another=completedResult(true);select(another,0,"BOTH",null,"other-result",200);
        answer(another,iid,1,"ANY","wrong-instance","m",404);answer(rid,Long.MAX_VALUE,1,"ANY","missing","m",404);
    }

    @Test void supportDoesNotMutateOriginalResultSessionRevisionOrPreResultQuestions() throws Exception {
        long rid=completedResult(true);String before=jdbc.queryForObject("SELECT result_json FROM exploration_result WHERE id=?",String.class,rid);
        String seal=jdbc.queryForObject("SELECT content_hash FROM exploration_result_seal WHERE result_id=?",String.class,rid);
        long sid=jdbc.queryForObject("SELECT session_id FROM exploration_result WHERE id=?",Long.class,rid),revision=sessionRevision(sid);int count=count("exploration_issued_question","session_id=?",sid);
        JsonNode original=response(get("/api/exploration/v1/results/"+rid).header("Authorization",owner),200);
        JsonNode selected=select(rid,0,"BOTH",null,"select",200);JsonNode q=selected.path("issued").get(0).path("question");answer(rid,q.path("issuedId").asLong(),1,q.path("options").get(0).path("code").asText(),"answer","m",200);
        select(rid,2,"ACTIVITY",null,"switch",200);getSupport(rid,owner,200);
        assertThat(jdbc.queryForObject("SELECT result_json FROM exploration_result WHERE id=?",String.class,rid)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT content_hash FROM exploration_result_seal WHERE result_id=?",String.class,rid)).isEqualTo(seal);
        assertThat(sessionRevision(sid)).isEqualTo(revision);assertThat(count("exploration_issued_question","session_id=?",sid)).isEqualTo(count);
        assertThat(response(get("/api/exploration/v1/results/"+rid).header("Authorization",owner),200)).isEqualTo(original);
    }

    @Test void concurrentSameKeySelectionCreatesOneSupportAndOneEightQuestionBatch() throws Exception {
        long rid=completedResult(true);var replies=parallel(()->support.select(OWNER,rid,"same",0,"BOTH",null));
        assertThat(replies.get(0).body()).isEqualTo(replies.get(1).body());assertThat(count("exploration_post_result","result_id=?",rid)).isEqualTo(1);
        assertThat(count("exploration_support_issued","post_result_id IN (SELECT id FROM exploration_post_result WHERE result_id=?)",rid)).isEqualTo(8);
    }

    @Test void concurrentDifferentSelectionsHaveOneSuccessAndOneRevisionConflict() throws Exception {
        long rid=completedResult(true);var outcomes=parallel(()->{try{support.select(OWNER,rid,UUID.randomUUID().toString(),0,"BOTH",null);return "SUCCESS";}catch(ExplorationApiException e){return e.getCode();}});
        assertThat(outcomes).containsExactlyInAnyOrder("SUCCESS","STALE_REVISION");
    }

    @Test void concurrentSameMutationWithDifferentHeadersStoresExactlyOneAnswer() throws Exception {
        long rid=completedResult(true);JsonNode selected=select(rid,0,"BOTH",null,"select",200),q=selected.path("issued").get(0).path("question");long iid=q.path("issuedId").asLong();String code=q.path("options").get(0).path("code").asText();
        var replies=parallel(()->support.answer(OWNER,rid,iid,UUID.randomUUID().toString(),1,code,"same-mutation"));
        assertThat(replies.get(0).body()).isEqualTo(replies.get(1).body());assertThat(count("exploration_support_answer","issued_id=?",iid)).isEqualTo(1);
    }

    @Test void partialIssuanceFailureRollsBackSupportAndSameKeyCanRetry() throws Exception {
        long rid=completedResult(true);
        jdbc.execute("CREATE TRIGGER test_support_issue_failure BEFORE INSERT ON exploration_support_issued FOR EACH ROW BEGIN IF NEW.ordinal=3 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic support issuance failure'; END IF; END");
        try{select(rid,0,"BOTH",null,"retry",500);assertThat(count("exploration_post_result","result_id=?",rid)).isZero();assertThat(count("exploration_idempotency","user_id=? AND idempotency_key='retry'",OWNER)).isZero();}
        finally{jdbc.execute("DROP TRIGGER test_support_issue_failure");}
        assertThat(select(rid,0,"BOTH",null,"retry",200).path("progress").path("issued").asInt()).isEqualTo(8);
    }

    @Test void answerCommitFailureRestoresPreviousCurrentAndSupportRevision() throws Exception {
        long rid=completedResult(true);JsonNode selected=select(rid,0,"BOTH",null,"select",200),q=selected.path("issued").get(0).path("question");long iid=q.path("issuedId").asLong();String code=q.path("options").get(0).path("code").asText();
        answer(rid,iid,1,code,"first","first",200);
        jdbc.execute("CREATE TRIGGER test_support_commit_failure BEFORE UPDATE ON exploration_idempotency FOR EACH ROW BEGIN IF NEW.operation='SUPPORT_ANSWER' AND NEW.response_status IS NOT NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic support commit failure'; END IF; END");
        try{answer(rid,iid,2,code,"retry","retry",500);assertThat(getSupport(rid,owner,200).path("supportRevision").asLong()).isEqualTo(2);assertThat(count("exploration_support_answer","issued_id=? AND is_current=true AND answer_revision=1",iid)).isEqualTo(1);assertThat(count("exploration_support_answer","issued_id=?",iid)).isEqualTo(1);}
        finally{jdbc.execute("DROP TRIGGER test_support_commit_failure");}
        assertThat(row(answer(rid,iid,2,code,"retry","retry",200),iid).path("answerRevision").asInt()).isEqualTo(2);
    }

    @Test void tamperedSupportSnapshotOrOriginalResultIsRejected() throws Exception {
        long rid=completedResult(true);JsonNode selected=select(rid,0,"BOTH",null,"select",200);long iid=selected.path("issued").get(0).path("question").path("issuedId").asLong();
        jdbc.update("UPDATE exploration_support_issued SET snapshot_json=JSON_SET(snapshot_json,'$.question.question_text','CHANGED') WHERE id=?",iid);
        assertThat(getSupport(rid,owner,503).path("code").asText()).isEqualTo("CATALOG_INTEGRITY_ERROR");
        jdbc.update("UPDATE exploration_result SET result_json=JSON_SET(result_json,'$.status','READY') WHERE id=?",rid);
        select(rid,1,"PLAN",null,"corrupt",503);
    }

    @Test void strictBodyHeadersAndIndependentSupportRevisionAreRequired() throws Exception {
        long rid=completedResult(true);
        response(post(path(rid)).header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(selectionBody(0,"BOTH",null)),400);
        for(String body:List.of("{}","{\"purpose\":\"BOTH\",\"expectedRevision\":0}","{\"purpose\":\"BOTH\",\"expectedSupportRevision\":0.5}","{\"purpose\":\"BOTH\",\"expectedSupportRevision\":\"0\"}","{\"purpose\":\"BOTH\",\"expectedSupportRevision\":0,\"userId\":8200000002}","{\"purpose\":\"BOTH\",\"expectedSupportRevision\":0,\"questions\":[]}","{\"purpose\":\"INVALID\",\"expectedSupportRevision\":0}"))
            response(post(path(rid)).header("Authorization",owner).header("Idempotency-Key","invalid").contentType(MediaType.APPLICATION_JSON).content(body),400);
        response(get(path(rid)),401);getSupport(rid,outside,404);
    }

    @Test void withdrawalCascadesAllSupportHistoryAndBlocksOldJwt() throws Exception {
        long rid=completedResult(true);JsonNode selected=select(rid,0,"BOTH",null,"select",200),q=selected.path("issued").get(0).path("question");long iid=q.path("issuedId").asLong();
        answer(rid,iid,1,q.path("options").get(0).path("code").asText(),"answer","m",200);
        deletion.delete(OWNER);
        assertThat(count("exploration_post_result","result_id=?",rid)).isZero();assertThat(count("exploration_support_issued","id=?",iid)).isZero();assertThat(count("exploration_support_answer","issued_id=?",iid)).isZero();
        getSupport(rid,owner,401);
    }

    private String path(long result){return "/api/exploration/v1/results/"+result+"/support";}
    private String selectionBody(long rev,String purpose,String target)throws Exception{var b=mapper.createObjectNode();b.put("expectedSupportRevision",rev);b.put("purpose",purpose);b.put("targetMajorCode",target);return mapper.writeValueAsString(b);}
    private JsonNode select(long rid,long rev,String purpose,String target,String key,int status)throws Exception{return response(post(path(rid)).header("Authorization",owner).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(selectionBody(rev,purpose,target)),status);}
    private JsonNode getSupport(long rid,String token,int status)throws Exception{return response(get(path(rid)).header("Authorization",token),status);}
    private JsonNode answer(long rid,long iid,long rev,String code,String key,String mutation,int status)throws Exception{return response(put(path(rid)+"/answers/"+iid).header("Authorization",owner).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("expectedSupportRevision",rev,"optionCode",code,"clientMutationId",mutation))),status);}
    private JsonNode row(JsonNode support,long iid){for(JsonNode row:support.path("issued"))if(row.path("question").path("issuedId").asLong()==iid)return row;throw new AssertionError("missing support question");}
}
