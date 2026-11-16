package com.jinroon.jobe.domain.exploration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.jinroon.jobe.domain.exploration.context.ResultContextService;
import com.jinroon.jobe.domain.exploration.support.PostResultSupportService;
import com.jinroon.jobe.domain.diagnosis.entity.DiagnosisSession;
import com.jinroon.jobe.domain.diagnosis.repository.DiagnosisSessionRepository;
import com.jinroon.jobe.domain.result.entity.DiagnosisResult;
import com.jinroon.jobe.domain.result.repository.DiagnosisResultRepository;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ResultContextApiTest extends CompletedExplorationTestSupport {
    @Autowired ResultContextService contexts;
    @Autowired PostResultSupportService support;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DiagnosisSessionRepository legacySessions;
    @Autowired DiagnosisResultRepository legacyResults;
    @Test void generalConsultationNeedsNoSupportAndNeverCreatesLegacySessionsOrPlans()throws Exception {
        long rid=completedResult(false);JsonNode prepared=prepare(rid,"CONSULTATION",0,null,"general",201);long id=prepared.path("contextId").asLong();
        assertThat(prepared.path("resultType").asText()).isEqualTo("EXPLORATION_V1");assertThat(prepared.path("resultKey").asText()).isEqualTo("EXPLORATION_V1:RESULT:"+rid);
        assertThat(prepared.path("contextKey").asText()).isEqualTo("EXPLORATION_V1:CONTEXT:"+id);assertThat(prepared.path("generationStatus").asText()).isEqualTo("NOT_REQUESTED");
        assertThat(prepared.path("generationAvailable").asBoolean()).isFalse();assertThat(prepared.path("status").asText()).isEqualTo("PREPARED");assertThat(getContext(id,owner,200)).isEqualTo(prepared);
        JsonNode stored=snapshot(id);assertThat(stored.path("support").path("initialized").asBoolean()).isFalse();assertThat(stored.path("support").path("appliedAnswers").isEmpty()).isTrue();
        assertThat(count("exploration_post_result","result_id=?",rid)).isZero();assertThat(count("consultation_sessions","user_id=?",OWNER)).isZero();assertThat(count("major_weekly_plans","1=1")).isZero();
    }
    @Test void plansWithoutCandidatesAreRejectedButGeneralConsultationIsAllowed()throws Exception {
        long rid=completedResult(false);assertThat(prepare(rid,"PLAN",0,"MAJOR_ROW_2","plan",409).path("code").asText()).isEqualTo("PLAN_TARGET_UNAVAILABLE");prepare(rid,"CONSULTATION",0,null,"consult",201);
    }
    @Test void planRequiresSelectedPlanSupportAndExactlyMatchingTarget()throws Exception {
        long rid=completedResult(true);String target=major(rid,0);
        assertThat(prepare(rid,"PLAN",0,null,"missing",422).path("code").asText()).isEqualTo("TARGET_MAJOR_REQUIRED");
        assertThat(prepare(rid,"PLAN",0,target,"support",409).path("code").asText()).isEqualTo("PLAN_SUPPORT_REQUIRED");
        support.select(OWNER,rid,"activity",0,"ACTIVITY",target);assertThat(prepare(rid,"PLAN",1,target,"activity-only",409).path("code").asText()).isEqualTo("PLAN_SUPPORT_REQUIRED");
        support.select(OWNER,rid,"plan",1,"PLAN",null);assertThat(prepare(rid,"PLAN",2,target,"unset",409).path("code").asText()).isEqualTo("SUPPORT_TARGET_MISMATCH");
        support.select(OWNER,rid,"target",2,"PLAN",target);assertThat(prepare(rid,"PLAN",3,target,"ready",201).path("appliedAnswerCount").asInt()).isZero();
    }
    @Test void targetOutsideResultIsRejectedAndBeyondFirstSevenIsPinned()throws Exception {
        long rid=completedResult(true);assertThat(prepare(rid,"CONSULTATION",0,"NOT_A_CANDIDATE","bad",422).path("code").asText()).isEqualTo("TARGET_MAJOR_NOT_IN_RESULT");
        String target=major(rid,7);support.select(OWNER,rid,"plan",0,"PLAN",target);JsonNode context=prepare(rid,"PLAN",1,target,"context",201);
        JsonNode pinned=snapshot(context.path("contextId").asLong()).path("targetMajor");assertThat(pinned.path("major_code").asText()).isEqualTo(target);assertThat(pinned.path("major_name").asText()).isNotBlank();assertThat(pinned.path("usage").asText()).isEqualTo("DEV_MAPPING_NOT_APPROVED_AI_EVIDENCE");
    }
    @Test void supportRevisionConflictDoesNotStoreContextOrIdempotentSuccess()throws Exception {
        long rid=completedResult(true);support.select(OWNER,rid,"support",0,"BOTH",null);
        assertThat(prepare(rid,"CONSULTATION",0,null,"stale",409).path("code").asText()).isEqualTo("STALE_REVISION");assertThat(count("exploration_result_context","result_id=?",rid)).isZero();assertThat(count("exploration_idempotency","user_id=? AND operation='CONTEXT_PREPARE'",OWNER)).isZero();
    }
    @Test void sameKeyReplaysFrozenResponseAfterSupportChangesAndNewKeyDeduplicatesSameInput()throws Exception {
        long rid=completedResult(true);JsonNode original=prepare(rid,"CONSULTATION",0,null,"first",201),same=prepare(rid,"CONSULTATION",0,null,"another",201);assertThat(same).isEqualTo(original);
        support.select(OWNER,rid,"change",0,"ACTIVITY",null);assertThat(prepare(rid,"CONSULTATION",0,null,"first",201)).isEqualTo(original);
        assertThat(prepare(rid,"PLAN",0,null,"first",409).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        JsonNode changed=prepare(rid,"CONSULTATION",1,null,"changed-input",201);assertThat(changed.path("contextId")).isNotEqualTo(original.path("contextId"));assertThat(count("exploration_result_context","result_id=?",rid)).isEqualTo(2);
    }
    @Test void supportAnswersAreFrozenAndLaterEditsDoNotMutatePreparedInput()throws Exception {
        long rid=completedResult(true);String target=major(rid,0);JsonNode state=support.select(OWNER,rid,"support",0,"PLAN",target).body(),q=state.path("issued").get(0).path("question");long issued=q.path("issuedId").asLong();String a=q.path("options").get(0).path("code").asText(),b=q.path("options").get(1).path("code").asText();
        support.answer(OWNER,rid,issued,"answer",1,a,"a");JsonNode first=prepare(rid,"PLAN",2,target,"first",201);long id=first.path("contextId").asLong();String before=snapshot(id).toString();
        support.answer(OWNER,rid,issued,"edit",2,b,"b");JsonNode second=prepare(rid,"PLAN",3,target,"second",201);
        assertThat(snapshot(id).toString()).isEqualTo(before);assertThat(getContext(id,owner,200)).isEqualTo(first);assertThat(snapshot(id).path("support").path("appliedAnswers").get(0).path("optionCode").asText()).isEqualTo(a);
        assertThat(snapshot(second.path("contextId").asLong()).path("support").path("appliedAnswers").get(0).path("optionCode").asText()).isEqualTo(b);
    }
    @Test void mismatchedReadinessAndExcludedAnswersAreOmittedWhileGenericActivityIsReusable()throws Exception {
        long rid=completedResult(true);String a=major(rid,0),b=major(rid,1);JsonNode selected=support.select(OWNER,rid,"support",0,"BOTH",a).body();JsonNode readiness=selected.path("issued").get(0).path("question"),activity=selected.path("issued").get(4).path("question");
        support.answer(OWNER,rid,readiness.path("issuedId").asLong(),"readiness",1,readiness.path("options").get(0).path("code").asText(),"r");
        support.answer(OWNER,rid,activity.path("issuedId").asLong(),"activity",2,activity.path("options").get(0).path("code").asText(),"a");
        support.select(OWNER,rid,"target",3,"BOTH",b);
        JsonNode context=prepare(rid,"PLAN",4,b,"context",201);assertThat(context.path("appliedAnswerCount").asInt()).isEqualTo(1);
        JsonNode applied=snapshot(context.path("contextId").asLong()).path("support").path("appliedAnswers").get(0);assertThat(applied.path("module").asText()).isEqualTo("EXPLORATION_ACTIVITY");
        JsonNode consult=prepare(rid,"CONSULTATION",4,a,"different-consult-target",201);assertThat(consult.path("appliedAnswerCount").asInt()).isEqualTo(1);
        support.select(OWNER,rid,"plan-only",4,"PLAN",b);JsonNode excluded=prepare(rid,"PLAN",5,b,"excluded",201);assertThat(excluded.path("appliedAnswerCount").asInt()).isZero();
        JsonNode collection=snapshot(excluded.path("contextId").asLong()).path("support");assertThat(collection.path("collectionBudgetLimited").asBoolean()).isTrue();assertThat(collection.path("omittedQuestionIds").size()).isEqualTo(4);assertThat(collection.path("collectionProgress").path("issued").asInt()).isEqualTo(8);
    }
    @Test void uncertainSupportIsPreservedAsUnknownWithoutInventedScores()throws Exception {
        long rid=completedResult(true);JsonNode state=support.select(OWNER,rid,"support",0,"PLAN",null).body(),q=state.path("issued").get(0).path("question"),options=q.path("options");
        support.answer(OWNER,rid,q.path("issuedId").asLong(),"unknown",1,options.get(options.size()-1).path("code").asText(),"u");
        JsonNode created=prepare(rid,"CONSULTATION",2,null,"context",201),answer=snapshot(created.path("contextId").asLong()).path("support").path("appliedAnswers").get(0);
        assertThat(answer.path("unknown").asBoolean()).isTrue();assertThat(answer.has("score")).isFalse();assertThat(answer.has("numericValue")).isFalse();
    }
    @Test void identicalRequestsWithSameOrDifferentKeysAreAtomicAndDeduplicated()throws Exception {
        long rid=completedResult(true);var same=parallel(()->contexts.prepare(OWNER,rid,"same","CONSULTATION",0,null));assertThat(same.get(0).body()).isEqualTo(same.get(1).body());
        var different=parallel(()->contexts.prepare(OWNER,rid,UUID.randomUUID().toString(),"CONSULTATION",0,null));assertThat(different.get(0).body()).isEqualTo(same.get(0).body());assertThat(different.get(1).body()).isEqualTo(same.get(0).body());assertThat(count("exploration_result_context","result_id=?",rid)).isEqualTo(1);
    }
    @Test void legacyIdCollisionNeverChangesLegacyResultOrCreatesLegacyWork()throws Exception {
        long rid=completedResult(true);long sid=legacySessions.saveAndFlush(DiagnosisSession.start(OTHER,2,"{}")).getId();long legacy=legacyResults.saveAndFlush(DiagnosisResult.create(sid,OTHER,"{\"private\":87}","{}","[]",UUID.randomUUID().toString())).getId();jdbc.update("UPDATE diagnosis_results SET id=? WHERE id=?",rid,legacy);
        String before=jdbc.queryForObject("SELECT competency_vector FROM diagnosis_results WHERE id=?",String.class,rid);JsonNode context=prepare(rid,"CONSULTATION",0,null,"typed",201);
        assertThat(context.path("resultKey").asText()).isEqualTo("EXPLORATION_V1:RESULT:"+rid);assertThat(jdbc.queryForObject("SELECT competency_vector FROM diagnosis_results WHERE id=?",String.class,rid)).isEqualTo(before);
        getContext(context.path("contextId").asLong(),other,404);assertThat(count("consultation_sessions","user_id=?",OTHER)).isZero();
    }
    @Test void ownerOnlyMetadataDoesNotExposeRawInputAndStrictRequestsCannotOverrideTypeOrVersions()throws Exception {
        long rid=completedResult(true);JsonNode prepared=prepare(rid,"CONSULTATION",0,null,"context",201);assertThat(prepared.toString()).doesNotContain("optionCode","answerText","snapshot_json","competency","weakness");
        getContext(prepared.path("contextId").asLong(),other,404);getContext(Long.MAX_VALUE,owner,404);response(get(contextPath(prepared.path("contextId").asLong())),401);
        for(String body:List.of("{}","{\"contextType\":\"OTHER\",\"expectedSupportRevision\":0}","{\"contextType\":\"CONSULTATION\",\"expectedSupportRevision\":\"0\"}","{\"contextType\":\"CONSULTATION\",\"expectedSupportRevision\":0.5}","{\"contextType\":\"CONSULTATION\",\"expectedRevision\":0}","{\"contextType\":\"CONSULTATION\",\"expectedSupportRevision\":0,\"diagnosisResultId\":1}","{\"contextType\":\"CONSULTATION\",\"expectedSupportRevision\":0,\"resultType\":\"LEGACY\"}","{\"contextType\":\"CONSULTATION\",\"expectedSupportRevision\":0,\"activityIds\":[]}"))response(post(path(rid)).header("Authorization",owner).header("Idempotency-Key","bad").contentType(MediaType.APPLICATION_JSON).content(body),400);
        response(post(path(rid)).header("Authorization",other).header("Idempotency-Key","foreign").contentType(MediaType.APPLICATION_JSON).content(body("CONSULTATION",0,null)),404);
        response(post(path(rid)).header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content(body("CONSULTATION",0,null)),400);
    }
    @Test void listUsesOwnerScopedIdPaginationAndRejectsInvalidOrDuplicateQuery()throws Exception {
        long rid=completedResult(true);JsonNode first=prepare(rid,"CONSULTATION",0,null,"first",201);String a=major(rid,0),b=major(rid,1);support.select(OWNER,rid,"support",0,"PLAN",a);prepare(rid,"PLAN",1,a,"plan",201);prepare(rid,"CONSULTATION",1,a,"consult",201);
        JsonNode page=response(get(path(rid)).param("limit","2").header("Authorization",owner),200);assertThat(page.path("items").size()).isEqualTo(2);assertThat(page.path("hasMore").asBoolean()).isTrue();
        prepare(rid,"CONSULTATION",1,b,"new",201);JsonNode last=response(get(path(rid)).param("limit","2").param("beforeContextId",page.path("nextBeforeContextId").asText()).header("Authorization",owner),200);assertThat(last.path("items").size()).isEqualTo(1);assertThat(last.path("items").get(0)).isEqualTo(first);assertThat(last.path("hasMore").asBoolean()).isFalse();
        for(String query:List.of("limit=0","limit=101","beforeContextId=-1","limit=2&limit=2","type=LEGACY","userId=1"))response(get(path(rid)+"?"+query).header("Authorization",owner),400);response(get(path(rid)).header("Authorization",other),404);
    }
    @Test void prepareFailureRollsBackContextAndRequestAndSameKeyCanRetry()throws Exception {
        long rid=completedResult(true);jdbc.execute("CREATE TRIGGER test_context_failure BEFORE INSERT ON exploration_result_context FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic context failure'");
        try{prepare(rid,"CONSULTATION",0,null,"retry",500);assertThat(count("exploration_result_context","result_id=?",rid)).isZero();assertThat(count("exploration_idempotency","user_id=? AND operation='CONTEXT_PREPARE'",OWNER)).isZero();}finally{jdbc.execute("DROP TRIGGER test_context_failure");}
        prepare(rid,"CONSULTATION",0,null,"retry",201);
    }
    @Test void idempotencyCommitFailureDoesNotLeaveAContext()throws Exception {
        long rid=completedResult(true);jdbc.execute("CREATE TRIGGER test_context_commit BEFORE UPDATE ON exploration_idempotency FOR EACH ROW BEGIN IF NEW.operation='CONTEXT_PREPARE' AND NEW.response_status IS NOT NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic context commit failure'; END IF; END");
        try{prepare(rid,"CONSULTATION",0,null,"retry",500);assertThat(count("exploration_result_context","result_id=?",rid)).isZero();}finally{jdbc.execute("DROP TRIGGER test_context_commit");}prepare(rid,"CONSULTATION",0,null,"retry",201);
    }
    @Test void sourceOrContextCorruptionFailsClosedWithoutRecalculation()throws Exception {
        long rid=completedResult(true);long id=prepare(rid,"CONSULTATION",0,null,"context",201).path("contextId").asLong();
        jdbc.update("UPDATE exploration_result_context SET snapshot_json=JSON_SET(snapshot_json,'$.support.purpose','changed') WHERE id=?",id);assertThat(getContext(id,owner,503).path("code").asText()).isEqualTo("CATALOG_INTEGRITY_ERROR");
        jdbc.update("UPDATE exploration_result SET result_json=JSON_SET(result_json,'$.status','READY') WHERE id=?",rid);prepare(rid,"CONSULTATION",0,null,"bad-source",503);
    }
    @Test void preparationDoesNotChangeOriginalResultSessionOrSupportRevision()throws Exception {
        long rid=completedResult(true),sid=resultSnapshot(rid).path("sessionId").asLong(),rev=sessionRevision(sid);String before=resultSnapshot(rid).toString();support.select(OWNER,rid,"support",0,"BOTH",null);
        prepare(rid,"CONSULTATION",1,null,"context",201);assertThat(resultSnapshot(rid).toString()).isEqualTo(before);assertThat(sessionRevision(sid)).isEqualTo(rev);assertThat(support.get(OWNER,rid).path("supportRevision").asLong()).isEqualTo(1);
    }
    @Test void futurePersistenceGuardRequiresTransactionOwnershipAndMatchingPurpose()throws Exception {
        long rid=completedResult(true);long id=prepare(rid,"CONSULTATION",0,null,"context",201).path("contextId").asLong();
        assertThatThrownBy(()->contexts.lockForUse(OWNER,id,"CONSULTATION")).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        var tx=new TransactionTemplate(transactions);var locked=tx.execute(status->contexts.lockForUse(OWNER,id,"CONSULTATION"));assertThat(locked.contextType()).isEqualTo("CONSULTATION");assertThat(locked.snapshot()).isEqualTo(snapshot(id));
        assertThatThrownBy(()->tx.execute(status->contexts.lockForUse(OWNER,id,"PLAN"))).hasMessageContaining("용도");assertThatThrownBy(()->tx.execute(status->contexts.lockForUse(OTHER,id,"CONSULTATION"))).isInstanceOf(com.jinroon.jobe.domain.exploration.api.ExplorationApiException.class);
    }
    @Test void withdrawalCascadesContextsAndLateUseCannotRecreateThem()throws Exception {
        long rid=completedResult(true);long id=prepare(rid,"CONSULTATION",0,null,"context",201).path("contextId").asLong();deletion.delete(OWNER);
        assertThat(count("exploration_result_context","result_id=?",rid)).isZero();assertThat(count("exploration_idempotency","user_id=? AND operation='CONTEXT_PREPARE'",OWNER)).isZero();response(get(contextPath(id)).header("Authorization",owner),401);
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->contexts.lockForUse(OWNER,id,"CONSULTATION"))).isInstanceOf(com.jinroon.jobe.global.exception.CustomException.class);assertThat(count("exploration_result_context","id=?",id)).isZero();
    }
    @Test void concurrentWithdrawalAndPreparationLeaveNoPersonalContext()throws Exception {
        long rid=completedResult(true);AtomicInteger calls=new AtomicInteger();var outcomes=parallel(()->{if(calls.getAndIncrement()==0){deletion.delete(OWNER);return "DELETED";}try{contexts.prepare(OWNER,rid,"race","CONSULTATION",0,null);return "PREPARED";}catch(com.jinroon.jobe.global.exception.CustomException e){return "BLOCKED";}catch(com.jinroon.jobe.domain.exploration.api.ExplorationApiException e){return "BLOCKED";}});
        assertThat(outcomes).contains("DELETED");assertThat(count("exploration_result_context","result_id=?",rid)).isZero();assertThat(count("exploration_idempotency","user_id=? AND operation='CONTEXT_PREPARE'",OWNER)).isZero();
    }
    private String path(long rid){return "/api/exploration/v1/results/"+rid+"/contexts";}
    private String contextPath(long id){return "/api/exploration/v1/contexts/"+id;}
    private String body(String type,long revision,String target)throws Exception{var body=mapper.createObjectNode();body.put("contextType",type);body.put("expectedSupportRevision",revision);body.put("targetMajorCode",target);return mapper.writeValueAsString(body);}
    private JsonNode prepare(long rid,String type,long revision,String target,String key,int status)throws Exception{return response(post(path(rid)).header("Authorization",owner).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body(type,revision,target)),status);}
    private JsonNode getContext(long id,String token,int status)throws Exception{return response(get(contextPath(id)).header("Authorization",token),status);}
    private JsonNode snapshot(long id)throws Exception{return mapper.readTree(jdbc.queryForObject("SELECT snapshot_json FROM exploration_result_context WHERE id=?",String.class,id));}
    private String major(long rid,int index)throws Exception{return resultSnapshot(rid).path("rankedCandidates").get(index).path("majorCode").asText();}
}
