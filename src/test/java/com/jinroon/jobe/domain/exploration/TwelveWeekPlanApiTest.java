package com.jinroon.jobe.domain.exploration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.ai.*;
import com.jinroon.jobe.domain.exploration.plan.*;
import com.jinroon.jobe.domain.exploration.support.PostResultSupportService;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

class TwelveWeekPlanApiTest extends CompletedExplorationTestSupport {
    @Autowired ExplorationPlanService service;
    @Autowired ExplorationPlanStore plans;
    @Autowired ExplorationPlanReservation reservations;
    @Autowired PostResultSupportService support;
    @Autowired ExplorationModelAvailability availability;
    @Autowired ExplorationAiContract contract;
    @Autowired ExplorationJobService jobs;
    @Autowired com.jinroon.jobe.domain.exploration.consultation.ExplorationConsultationService chat;
    @Autowired com.jinroon.jobe.domain.exploration.consultation.ConsultationMessageService chatMessages;
    @MockitoBean ExplorationJobClient remote;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.jinroon.jobe.domain.exploration.activity.ActivityRecommendationService activityRecommendations;
    Set<String> owners=new HashSet<>();
    record Input(long result,String major,long revision){}
    @BeforeEach void config(){ReflectionTestUtils.setField(availability,"modelEnabled",true);ReflectionTestUtils.setField(availability,"erasureEnabled",true);
        when(remote.command(anyString(),anyString(),anyString(),any())).thenReturn(mapper.createObjectNode().put("status","QUEUED"));
        doAnswer(c->envelope(c.getArgument(1),c.getArgument(2))).when(remote).executeModel(anyString(),anyString(),any());}
    @Override @AfterEach void cleanup(){owners.addAll(jdbc.queryForList("SELECT owner_key FROM exploration_ai_owner WHERE user_id=?",String.class,OWNER));super.cleanup();for(String owner:owners)jdbc.update("DELETE FROM exploration_ai_erasure_outbox WHERE owner_key=?",owner);ReflectionTestUtils.setField(availability,"modelEnabled",false);ReflectionTestUtils.setField(availability,"erasureEnabled",false);}
    String major(long result,int index)throws Exception{return resultSnapshot(result).path("rankedCandidates").get(index).path("majorCode").asText();}
    Input input()throws Exception{long r=completedResult(true);String m=major(r,0);support.select(OWNER,r,"support",0,"PLAN",m);return new Input(r,m,1);}
    String path(long result){return "/api/exploration/v1/results/"+result+"/plans";}
    String detail(long id){return "/api/exploration/v1/plans/"+id;}
    String body(String major,Long rev)throws Exception{var b=mapper.createObjectNode().put("majorCode",major);if(rev==null)b.putNull("supportRevision");else b.put("supportRevision",rev);return b.toString();}
    JsonNode create(Input in,String key,Long rev,int status)throws Exception{return response(post(path(in.result())).header("Authorization",owner).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(body(in.major(),rev)),status);}
    JsonNode retry(long id)throws Exception{return response(post(detail(id)+"/retry").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content("{}"),200);}
    String mutation(long id){return jdbc.queryForObject("SELECT generation_mutation FROM exploration_plan WHERE id=?",String.class,id);}
    ObjectNode envelope(String mutation,JsonNode original){
        ObjectNode bound=original.deepCopy();var ev=(ObjectNode)bound.path("evidence");ev.put("bindingStatus","BOUND").put("datasetVersion","TEST_PLAN12_DATA");var refs=ev.putArray("referenceKeys");refs.add("ref_"+"a".repeat(64));
        if(original.path("input").path("targetName").isNull())for(int i=1;i<original.path("input").path("candidateNames").size();i++)refs.add("ref_"+String.format("%064x",i));
        bound.put("inputHash",contract.inputHash(bound));
        var response=mapper.createObjectNode();for(String f:List.of("contractVersion","requestId","inputHash","purpose"))response.set(f,bound.path(f));response.put("status","SUCCEEDED").put("changesRecommendation",false).putNull("reasonCode");
        var result=response.putObject("result").put("targetName",bound.path("input").path("targetName").asText());var weeks=result.putArray("weeks");
        for(int i=1;i<=12;i++){var w=weeks.addObject().put("week",i).put("phase",i<=4?"INFO":i<=8?"BASIC_LEARNING":"SMALL_ACTIVITY").put("title","SYNTHETIC week "+i).put("expectedOutput","SYNTHETIC note");w.putArray("tasks").add("SYNTHETIC educational task");w.putArray("referenceKeys").add("ref_"+"a".repeat(64));}
        var out=mapper.createObjectNode().put("transportVersion","EXPLORATION_MODEL_EXECUTION_V1_0").put("mode","MODEL").put("generationAuthorized",true).put("mutationKey",mutation).put("originalInputHash",original.path("inputHash").asText()).put("sourceContextHash",original.path("sourceContextHash").asText()).put("bindingHash","a".repeat(64)).put("jobId",1);
        if(original.path("purpose").asText().equals("CONSULTATION")){var answer=mapper.createObjectNode().put("summary","SYNTHETIC conversation answer");answer.putArray("questions");answer.putArray("referenceKeys").add("ref_"+"a".repeat(64));response.set("result",answer);}
        out.set("boundRequest",bound);out.set("response",response);out.putObject("providerMetadata").put("provider","openai").put("model","TEST_PLAN_MODEL").put("promptVersion",ExplorationAiContract.promptVersion(original));return out;
    }
    @Test void createsTwelveGroundedWeeksAndReadsSameOwnedImmutablePlan()throws Exception{
        var in=input();long legacy=unrelatedLegacyPlan();var legacyRow=jdbc.queryForMap("SELECT * FROM major_weekly_plans WHERE id=?",legacy);String original=jdbc.queryForObject("SELECT result_json FROM exploration_result WHERE id=?",String.class,in.result());
        var plan=create(in,"one",null,200);long id=plan.path("planId").asLong();assertThat(plan.path("status").asText()).isEqualTo("READY");assertThat(plan.path("weeks").size()).isEqualTo(12);
        assertThat(plan.path("supportRevision").asLong()).isEqualTo(1);assertThat(plan.path("sources").size()).isEqualTo(1);
        assertThat(plan.toString()).doesNotContain("referenceKeys","providerMetadata","inputHash","bindingHash");assertThat(plan.path("createdAt").asText()).endsWith("Z");
        assertThat(response(get(detail(id)).header("Authorization",owner),200)).isEqualTo(plan);
        ReflectionTestUtils.setField(availability,"modelEnabled",false);assertThat(create(in,"one",null,200)).isEqualTo(plan);assertThat(retry(id)).isEqualTo(plan);
        var list=response(get(path(in.result())).header("Authorization",owner),200);assertThat(list.size()).isEqualTo(1);assertThat(list.get(0).has("weeks")).isFalse();
        assertThat(jdbc.queryForObject("SELECT result_json FROM exploration_result WHERE id=?",String.class,in.result())).isEqualTo(original);
        assertThat(jdbc.queryForMap("SELECT * FROM major_weekly_plans WHERE id=?",legacy)).isEqualTo(legacyRow);
        verify(remote,times(1)).executeModel(anyString(),anyString(),any());
    }
    @Test void twoMajorsHaveDifferentApprovedActivitiesAndPinnedPlansStayReadable() throws Exception {
        var in=input();
        String second=major(in.result(),1);
        var firstBasis=activityBasis(in.major(),"작품 표현 비교하기","표현 표시가 있는 두 문장");
        var secondBasis=activityBasis(second,"자료 값 비교하기","비교 표");
        doReturn(firstBasis).when(activityRecommendations).planBasis(OWNER,in.result(),in.major());
        doReturn(true).when(activityRecommendations).planBasisApproved(firstBasis,in.major());
        var first=create(in,"first-approved",in.revision(),200);

        support.select(OWNER,in.result(),"another-target",1,"PLAN",second);
        doReturn(secondBasis).when(activityRecommendations).planBasis(OWNER,in.result(),second);
        doReturn(true).when(activityRecommendations).planBasisApproved(secondBasis,second);
        var another=create(new Input(in.result(),second,2),"second-approved",2L,200);
        assertThat(first.path("weeks").size()).isEqualTo(12);
        assertThat(first.path("weeks").get(8).path("title").asText()).contains("작품 표현 비교하기");
        assertThat(first.path("weeks").get(8).path("expectedOutput").asText()).isEqualTo("표현 표시가 있는 두 문장");
        assertThat(another.path("weeks").get(8).path("title").asText()).contains("자료 값 비교하기");
        assertThat(another.path("weeks").get(8).path("expectedOutput").asText()).isEqualTo("비교 표");
        assertThat(first.path("sources").get(1).path("kind").asText()).isEqualTo("ACTIVITY_CATALOG");
        assertThat(response(get(detail(first.path("planId").asLong())).header("Authorization",owner),200)).isEqualTo(first);
    }

    private ObjectNode activityBasis(String major,String title,String output) {
        var basis=mapper.createObjectNode().put("catalogVersion","TEST_ACTIVITIES");
        var practice=basis.putArray("activities").addObject().put("major_code",major).put("kind","SMALL_TASK")
                .put("title",title).put("body_markdown","두 가지 예를 읽어요.")
                .put("alternative","예시 한 가지만 읽어요.").put("example_output","확인한 차이 한 가지");
        practice.putArray("steps").add("두 예시를 비교해요.");
        practice.putArray("artifacts").add(output);
        return basis;
    }
    @Test void requiresSelectedPlanSupportCandidateAndCurrentRevision()throws Exception{
        long r=completedResult(true);var in=new Input(r,major(r,0),0);create(in,"none",0L,409);
        support.select(OWNER,r,"activity",0,"ACTIVITY",in.major());create(in,"activity",1L,409);
        support.select(OWNER,r,"plan",1,"PLAN",in.major());create(in,"stale",1L,409);
        response(post(path(r)).header("Authorization",owner).header("Idempotency-Key","outside").contentType(MediaType.APPLICATION_JSON).content(body("OUTSIDE",2L)),422);
        create(in,"ok",2L,200);assertThat(count("exploration_plan","result_id=?",r)).isEqualTo(1);
        long none=completedResult(false);response(post(path(none)).header("Authorization",owner).header("Idempotency-Key","no-candidates").contentType(MediaType.APPLICATION_JSON).content(body("OUTSIDE",0L)),409);
        assertThat(response(get(path(r)).header("Authorization",owner),200).get(0).path("status").asText()).isEqualTo("READY");
    }
    @Test void acceptsCanonicalTargetAliasAndRejectsUnexpectedBodiesAndForeignAccess()throws Exception{
        var in=input();var canonical=mapper.createObjectNode().put("targetMajorCode",in.major()).put("supportRevision",in.revision());
        var plan=response(post(path(in.result())).header("Authorization",owner).header("Idempotency-Key","canonical").contentType(MediaType.APPLICATION_JSON).content(canonical.toString()),200);long id=plan.path("planId").asLong();
        response(get(path(in.result())).header("Authorization",other),404);response(get(detail(id)).header("Authorization",other),404);
        response(post(path(in.result())).header("Authorization",other).header("Idempotency-Key","foreign").contentType(MediaType.APPLICATION_JSON).content(body(in.major(),1L)),404);
        response(get(detail(Long.MAX_VALUE)).header("Authorization",owner),404);response(get(detail(id)),401);
        for(String b:List.of("{}","null","{\"majorCode\":\"X\",\"supportRevision\":1.5}","{\"majorCode\":\"X\",\"supportRevision\":true}","{\"majorCode\":\"X\",\"targetMajorCode\":\"X\",\"supportRevision\":1}"))response(post(path(in.result())).header("Authorization",owner).header("Idempotency-Key","bad").contentType(MediaType.APPLICATION_JSON).content(b),400);
        response(get(path(in.result())+"?limit=1").header("Authorization",owner),400);
    }
    @Test void supportChangesDoNotOverwritePastPlansOrReinterpretIdempotencyKeys()throws Exception{
        var in=input();var old=create(in,"key",null,200);String other=major(in.result(),1);support.select(OWNER,in.result(),"change",1,"PLAN",other);
        assertThat(create(in,"key",null,200)).isEqualTo(old);create(new Input(in.result(),other,2),"key",null,409);
        var fresh=create(new Input(in.result(),other,2),"new",2L,200);assertThat(fresh.path("majorCode").asText()).isEqualTo(other);
        assertThat(response(get(path(in.result())).header("Authorization",owner),200).size()).isEqualTo(2);
    }
    @Test void transientFailureHasNoFabricatedWeeksAndExplicitRetryUsesSameTask()throws Exception{
        var in=input();doThrow(new ExplorationJobClient.Unavailable()).when(remote).executeModel(anyString(),anyString(),any());
        var failed=create(in,"key",null,200);long id=failed.path("planId").asLong();assertThat(failed.path("status").asText()).isEqualTo("FAILED");assertThat(failed.path("retryable").asBoolean()).isTrue();assertThat(failed.path("weeks").isEmpty()).isTrue();
        for(int i=0;i<2;i++)response(get(detail(id)).header("Authorization",owner),200);retry(id);verify(remote,times(1)).executeModel(anyString(),anyString(),any());
        jdbc.update("UPDATE exploration_generation_task SET next_attempt_at=UTC_TIMESTAMP(6) WHERE mutation_key=?",mutation(id));
        doAnswer(c->envelope(c.getArgument(1),c.getArgument(2))).when(remote).executeModel(anyString(),anyString(),any());
        assertThat(retry(id).path("status").asText()).isEqualTo("READY");assertThat(count("exploration_plan","result_id=?",in.result())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempt FROM exploration_generation_task WHERE mutation_key=?",Integer.class,mutation(id))).isEqualTo(2);
    }
    @Test void insufficientEvidenceDoesNotInvokeModelOrInventAPlan()throws Exception{
        var in=input();when(remote.command(anyString(),anyString(),anyString(),any())).thenReturn(mapper.createObjectNode().put("status","INSUFFICIENT_EVIDENCE"));
        var p=create(in,"no-evidence",null,200);assertThat(p.path("status").asText()).isEqualTo("FAILED");assertThat(p.path("failureCode").asText()).isEqualTo("EVIDENCE_UNAVAILABLE");assertThat(p.path("sources").isEmpty()).isTrue();assertThat(p.path("retryable").asBoolean()).isFalse();
        retry(p.path("planId").asLong());verify(remote,never()).executeModel(anyString(),anyString(),any());
    }
    @Test void malformedTwelveWeekOutputIsTerminalWithoutPublication()throws Exception{
        var in=input();doAnswer(c->{var e=envelope(c.getArgument(1),c.getArgument(2));((ObjectNode)e.path("response").path("result").path("weeks").get(4)).put("phase","INFO");return e;}).when(remote).executeModel(anyString(),anyString(),any());
        var p=create(in,"bad",null,200);assertThat(p.path("status").asText()).isEqualTo("FAILED");assertThat(p.path("retryable").asBoolean()).isFalse();assertThat(p.path("weeks").isEmpty()).isTrue();
        assertThat(jdbc.queryForObject("SELECT publication_json IS NULL FROM exploration_plan WHERE id=?",Boolean.class,p.path("planId").asLong())).isTrue();
    }
    @Test void concurrentSameKeyDoesNotCallModelTwiceAndGetIsReadOnly()throws Exception{
        var in=input();var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        doAnswer(c->{entered.countDown();assertThat(finish.await(20,TimeUnit.SECONDS)).isTrue();return envelope(c.getArgument(1),c.getArgument(2));}).when(remote).executeModel(anyString(),anyString(),any());
        Future<JsonNode> first=pool.submit(()->service.create(OWNER,in.result(),"key",in.major(),null));
        try{assertThat(entered.await(20,TimeUnit.SECONDS)).isTrue();var same=create(in,"key",null,200);assertThat(same.path("status").asText()).isEqualTo("GENERATING");assertThat(response(get(detail(same.path("planId").asLong())).header("Authorization",owner),200).path("weeks").isEmpty()).isTrue();finish.countDown();assertThat(first.get(20,TimeUnit.SECONDS).path("status").asText()).isEqualTo("READY");}
        finally{finish.countDown();pool.shutdownNow();pool.awaitTermination(5,TimeUnit.SECONDS);}
        verify(remote,times(1)).executeModel(anyString(),anyString(),any());
    }
    @Test void cancellationDuringModelCallBlocksLatePublication()throws Exception{
        var in=input();doAnswer(c->{long id=jdbc.queryForObject("SELECT id FROM exploration_plan WHERE generation_mutation=?",Long.class,(Object)c.getArgument(1));service.cancel(OWNER,id);return envelope(c.getArgument(1),c.getArgument(2));}).when(remote).executeModel(anyString(),anyString(),any());
        var p=create(in,"key",null,200);assertThat(p.path("status").asText()).isEqualTo("FAILED");assertThat(p.path("failureCode").asText()).isEqualTo("CANCELLED");assertThat(p.path("weeks").isEmpty()).isTrue();
    }
    @Test void withdrawalDuringModelCallDeletesPlanAndPreventsRecreation()throws Exception{
        var in=input();doAnswer(c->{owners.addAll(jdbc.queryForList("SELECT owner_key FROM exploration_ai_owner WHERE user_id=?",String.class,OWNER));deletion.delete(OWNER);return envelope(c.getArgument(1),c.getArgument(2));}).when(remote).executeModel(anyString(),anyString(),any());
        assertThatThrownBy(()->service.create(OWNER,in.result(),"key",in.major(),null)).isInstanceOf(RuntimeException.class);assertThat(count("exploration_plan","result_id=?",in.result())).isZero();
    }
    @Test void publicationFailureRollsBackOwnedResultAndPlanTogether()throws Exception{
        var in=input();jdbc.execute("CREATE TRIGGER test_plan_publication_failure BEFORE UPDATE ON exploration_plan FOR EACH ROW BEGIN IF NEW.publication_json IS NOT NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SYNTHETIC plan failure'; END IF; END");
        try{assertThatThrownBy(()->service.create(OWNER,in.result(),"key",in.major(),null)).isInstanceOf(RuntimeException.class);}
        finally{jdbc.execute("DROP TRIGGER test_plan_publication_failure");}
        assertThat(jdbc.queryForObject("SELECT publication_json IS NULL FROM exploration_plan WHERE result_id=?",Boolean.class,in.result())).isTrue();
        assertThat(jdbc.queryForObject("SELECT result_json IS NULL FROM exploration_generation_task WHERE user_id=?",Boolean.class,OWNER)).isTrue();
    }
    @Test void corruptPublicationIsUnavailableInDetailButDoesNotBlockHealthyListItems()throws Exception{
        var in=input();var a=create(in,"a",null,200);var b=create(in,"b",null,200);
        jdbc.update("UPDATE exploration_generation_task SET result_json=JSON_OBJECT() WHERE mutation_key=?",mutation(a.path("planId").asLong()));
        response(get(detail(a.path("planId").asLong())).header("Authorization",owner),503);
        var list=response(get(path(in.result())).header("Authorization",owner),200);assertThat(list.size()).isEqualTo(2);assertThat(list.get(0).path("status").asText()).isEqualTo("READY");assertThat(list.get(1).path("failureCode").asText()).isEqualTo("PLAN_INTEGRITY_ERROR");
    }
    @Test void disabledModelDoesNotCreatePlanAndCompletedPlanCannotBeCancelled()throws Exception{
        var in=input();ReflectionTestUtils.setField(availability,"modelEnabled",false);create(in,"disabled",null,503);assertThat(count("exploration_plan","result_id=?",in.result())).isZero();
        long none=completedResult(false);response(post(path(none)).header("Authorization",owner).header("Idempotency-Key","none").contentType(MediaType.APPLICATION_JSON).content(body("OUTSIDE",0L)),409);
        ReflectionTestUtils.setField(availability,"modelEnabled",true);var p=create(in,"ok",null,200);response(delete(detail(p.path("planId").asLong())+"/generation").header("Authorization",owner),409);
    }
    @Test void expiredLeaseCanBeExplicitlyRetriedWithoutAcceptingLateOutput()throws Exception{
        var in=input();doAnswer(c->{jdbc.update("UPDATE exploration_generation_task SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE mutation_key=?",(Object)c.getArgument(1));return envelope(c.getArgument(1),c.getArgument(2));}).when(remote).executeModel(anyString(),anyString(),any());
        var p=create(in,"key",null,200);assertThat(p.path("failureCode").asText()).isEqualTo("LEASE_EXPIRED");assertThat(p.path("retryable").asBoolean()).isTrue();
        doAnswer(c->envelope(c.getArgument(1),c.getArgument(2))).when(remote).executeModel(anyString(),anyString(),any());assertThat(retry(p.path("planId").asLong()).path("status").asText()).isEqualTo("READY");
    }
    @Test void pendingCancellationIsOwnedIdempotentAndLocalWhenRemoteIsDown()throws Exception{
        var in=input();var d=reservations.reserve(OWNER,in.result(),"key",in.major(),null);
        response(delete(detail(d.id())+"/generation").header("Authorization",other),404);
        when(remote.command(eq("cancel"),anyString(),anyString(),any())).thenThrow(new ExplorationJobClient.Unavailable());
        response(delete(detail(d.id())+"/generation").header("Authorization",owner),200);response(delete(detail(d.id())+"/generation").header("Authorization",owner),200);
        assertThat(retry(d.id()).path("failureCode").asText()).isEqualTo("CANCELLED");verify(remote,never()).executeModel(anyString(),anyString(),any());
    }
    @Test void failedReservationCannotLeaveAPlanWithoutItsJob()throws Exception{
        var in=input();jdbc.execute("CREATE TRIGGER test_plan_reservation_failure BEFORE INSERT ON exploration_generation_task FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SYNTHETIC reservation failure'");
        try{assertThatThrownBy(()->reservations.reserve(OWNER,in.result(),"key",in.major(),null)).isInstanceOf(RuntimeException.class);}
        finally{jdbc.execute("DROP TRIGGER test_plan_reservation_failure");}
        assertThat(count("exploration_plan","result_id=?",in.result())).isZero();assertThat(count("exploration_result_context","result_id=?",in.result())).isZero();
    }
    @Test void declinedResultIsPreservedAsFailureAndNeverAsAReadyPlan()throws Exception{
        var in=input();doAnswer(c->{var e=envelope(c.getArgument(1),c.getArgument(2));((ObjectNode)e.path("response")).put("status","DECLINED").put("reasonCode","OUT_OF_SCOPE").putNull("result");return e;}).when(remote).executeModel(anyString(),anyString(),any());
        var p=create(in,"key",null,200);assertThat(p.path("status").asText()).isEqualTo("FAILED");assertThat(p.path("failureCode").asText()).isEqualTo("MODEL_DECLINED");assertThat(p.path("weeks").isEmpty()).isTrue();assertThat(retry(p.path("planId").asLong())).isEqualTo(p);
    }
    @Test void diagnosisConversationAndExplicitPlanFlowPreservesTheOriginalResult()throws Exception{
        long result=completedResult(true);String sealed=jdbc.queryForObject("SELECT result_json FROM exploration_result WHERE id=?",String.class,result);
        assertThat(count("exploration_plan","result_id=?",result)).isZero();long consultation=chat.open(OWNER,result,"open").consultationId();chatMessages.send(OWNER,consultation,"question","어떤 자료를 확인할까요?");
        assertThat(count("exploration_plan","result_id=?",result)).isZero();String target=major(result,0);support.select(OWNER,result,"goal",0,"PLAN",target);
        var plan=service.create(OWNER,result,"explicit-plan",target,1L);assertThat(plan.path("status").asText()).isEqualTo("READY");assertThat(chat.open(OWNER,result,"resume").messages()).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT result_json FROM exploration_result WHERE id=?",String.class,result)).isEqualTo(sealed);
    }
}
