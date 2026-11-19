package com.jinroon.jobe.domain.exploration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.ai.*;
import com.jinroon.jobe.domain.exploration.consultation.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

class ConsultationMessageApiTest extends CompletedExplorationTestSupport {
    @Autowired ExplorationConsultationService consultations;
    @Autowired ConsultationMessageService messages;
    @Autowired ConsultationMessageReservation reservations;
    @Autowired ExplorationModelAvailability availability;
    @Autowired OwnedGenerationStore generations;
    @Autowired ExplorationJobService jobs;
    @Autowired ExplorationAiContract contract;
    @MockitoBean ExplorationJobClient remote;
    final Set<String> owners=new HashSet<>();
    @BeforeEach void model(){
        ReflectionTestUtils.setField(availability,"modelEnabled",true);ReflectionTestUtils.setField(availability,"erasureEnabled",true);
        when(remote.command(anyString(),anyString(),anyString(),any())).thenReturn(mapper.createObjectNode().put("status","QUEUED"));
        doAnswer(c->envelope(c.getArgument(1),c.getArgument(2),"SYNTHETIC ANSWER")).when(remote).executeModel(anyString(),anyString(),any());
    }
    @Override @AfterEach void cleanup(){
        owners.addAll(jdbc.queryForList("SELECT owner_key FROM exploration_ai_owner WHERE user_id=?",String.class,OWNER));
        super.cleanup();for(String owner:owners)jdbc.update("DELETE FROM exploration_ai_erasure_outbox WHERE owner_key=?",owner);
        ReflectionTestUtils.setField(availability,"modelEnabled",false);ReflectionTestUtils.setField(availability,"erasureEnabled",false);
    }
    long open()throws Exception{return consultations.open(OWNER,completedResult(true),"open").consultationId();}
    String path(long c){return "/api/exploration/v1/consultations/"+c+"/messages";}
    JsonNode send(long c,String key,String question,int status)throws Exception{return response(post(path(c)).header("Authorization",owner).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("content",question))),status);}
    String mutation(long c){return jdbc.queryForObject("SELECT generation_mutation FROM exploration_consultation_turn WHERE consultation_id=? ORDER BY turn_no DESC LIMIT 1",String.class,c);}
    long context(long c){return jdbc.queryForObject("SELECT context_id FROM exploration_consultation WHERE id=?",Long.class,c);}
    ObjectNode envelope(String mutation,JsonNode original,String text){
        ObjectNode bound=original.deepCopy();var ev=(ObjectNode)bound.path("evidence");ev.put("bindingStatus","BOUND").put("datasetVersion","TEST_CHAT_DATA");var refs=ev.putArray("referenceKeys");
        for(int i=0;i<bound.path("input").path("candidateNames").size();i++)refs.add("ref_"+String.format("%064x",i));
        bound.put("inputHash",contract.inputHash(bound));var response=mapper.createObjectNode();
        for(String key:List.of("contractVersion","requestId","inputHash","purpose"))response.set(key,bound.path(key));
        response.put("status","SUCCEEDED").put("changesRecommendation",false).putNull("reasonCode");
        var r=response.putObject("result").put("summary",text);r.putArray("questions");r.putArray("referenceKeys").add(refs.get(0));
        var out=mapper.createObjectNode().put("transportVersion","EXPLORATION_MODEL_EXECUTION_V1_0").put("mode","MODEL").put("generationAuthorized",true)
            .put("mutationKey",mutation).put("originalInputHash",original.path("inputHash").asText()).put("sourceContextHash",original.path("sourceContextHash").asText()).put("bindingHash","a".repeat(64)).put("jobId",1);
        out.set("boundRequest",bound);out.set("response",response);out.putObject("providerMetadata").put("provider","openai").put("model","TEST_CHAT_MODEL").put("promptVersion","EXPLORATION_CONVERSATION_V1_0");return out;
    }
    @Test void sendsOwnedPairReplaysWithoutProviderAndResumesVerifiedHistory()throws Exception{
        long c=open();var first=messages.send(OWNER,c,"q1","무엇을 확인할까요?");
        assertThat(first.fieldNames()).toIterable().containsExactlyInAnyOrder("userMessage","assistantMessage");
        assertThat(first.path("userMessage").path("role").asText()).isEqualTo("user");
        assertThat(first.path("userMessage").path("evidenceStatus").isNull()).isTrue();
        assertThat(first.path("assistantMessage").path("evidenceStatus").asText()).isEqualTo("GROUNDED");
        assertThat(first.path("assistantMessage").path("sources").get(0).path("version").asText()).isEqualTo("TEST_CHAT_DATA");
        ReflectionTestUtils.setField(availability,"modelEnabled",false);assertThat(send(c,"q1","무엇을 확인할까요?",200)).isEqualTo(first);
        long result=jdbc.queryForObject("SELECT result_id FROM exploration_consultation WHERE id=?",Long.class,c);
        var resume=response(post("/api/exploration/v1/results/"+result+"/consultations").header("Authorization",owner).header("Idempotency-Key","resume").contentType(MediaType.APPLICATION_JSON).content("{}"),200);
        assertThat(resume.path("messages").size()).isEqualTo(2);assertThat(resume.path("messages").get(1)).isEqualTo(first.path("assistantMessage"));
        assertThat(first.toString()).doesNotContain("ownerKey","inputHash","requestId","bindingHash","providerMetadata");
        verify(remote,times(1)).executeModel(anyString(),anyString(),any());
    }
    @Test void sendsServerSelectedPairsAndDropsOldWholePairsToFitBudget()throws Exception{
        long c=open();List<JsonNode> inputs=new ArrayList<>();
        doAnswer(call->{JsonNode r=call.getArgument(2);inputs.add(r.deepCopy());return envelope(call.getArgument(1),r,"A".repeat(4000));}).when(remote).executeModel(anyString(),anyString(),any());
        for(int i=0;i<4;i++)send(c,"q"+i,"Q".repeat(1000)+i,200);
        assertThat(inputs.get(0).path("input").path("conversation").path("history").size()).isZero();
        assertThat(inputs.get(3).path("input").path("conversation").path("history").size()).isEqualTo(2);
        assertThat(inputs.get(3).path("input").path("conversation").path("history").get(0).path("content").asText()).endsWith("2");
        assertThat(inputs.get(3).path("contractVersion").asText()).isEqualTo("EXPLORATION_AI_CONTRACT_V1_1");
    }
    @Test void rejectsBodyConflictForeignOwnershipAndCallerSuppliedHistory()throws Exception{
        long c=open();send(c,"same","First",200);assertThat(send(c,"same","Changed",409).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        response(post(path(c)).header("Authorization",other).header("Idempotency-Key","q").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Q\"}"),404);
        response(post(path(Long.MAX_VALUE)).header("Authorization",owner).header("Idempotency-Key","q").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Q\"}"),404);
        response(post(path(c)).header("Idempotency-Key","q").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Q\"}"),401);
        for(String body:List.of("{}","null","{\"content\":42}","{\"content\":\"Q\",\"history\":[]}","{\"content\":\"Q\",\"role\":\"assistant\"}"))response(post(path(c)).header("Authorization",owner).header("Idempotency-Key","q").contentType(MediaType.APPLICATION_JSON).content(body),400);
        send(c,"blank"," \t",400);send(c,"long","a".repeat(2001),400);
        response(post(path(c)).header("Authorization",owner).contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Q\"}"),400);
        response(post(path(c)+"?mode=MOCK").header("Authorization",owner).header("Idempotency-Key","q").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"Q\"}"),400);
        verify(remote,times(1)).executeModel(anyString(),anyString(),any());
    }
    @Test void disabledModelDoesNotReserveAnyTurnAndUnicodeQuestionUsesCodepoints()throws Exception{
        long c=open();ReflectionTestUtils.setField(availability,"modelEnabled",false);send(c,"q","Q",503);
        assertThat(count("exploration_consultation_turn","consultation_id=?",c)).isZero();
        ReflectionTestUtils.setField(availability,"modelEnabled",true);send(c,"unicode","😀".repeat(2000),200);
    }
    @Test void transientFailureKeepsOneQuestionAndRetriesSameTaskWithinBudget()throws Exception{
        long c=open();doThrow(new ExplorationJobClient.Unavailable()).when(remote).executeModel(anyString(),anyString(),any());send(c,"q","Q",503);
        String mutation=mutation(c);assertThat(count("exploration_consultation_message","turn_id IN (SELECT id FROM exploration_consultation_turn WHERE consultation_id=?)",c)).isZero();
        send(c,"other","Other",409);send(c,"q","Q",409);
        jdbc.update("UPDATE exploration_generation_task SET next_attempt_at=UTC_TIMESTAMP(6) WHERE mutation_key=?",mutation);
        doAnswer(call->envelope(call.getArgument(1),call.getArgument(2),"RETRIED")).when(remote).executeModel(anyString(),anyString(),any());
        send(c,"q","Q",200);assertThat(mutation(c)).isEqualTo(mutation);
        assertThat(jdbc.queryForObject("SELECT attempt FROM exploration_generation_task WHERE mutation_key=?",Integer.class,mutation)).isEqualTo(2);
    }
    @Test void permanentFailureIsTerminalAndNewKeyCanStartAnotherTurn()throws Exception{
        long c=open();doThrow(new ExplorationJobClient.Unavailable(ExplorationJobClient.Failure.INVALID_RESPONSE)).when(remote).executeModel(anyString(),anyString(),any());send(c,"bad","Q",503);
        send(c,"bad","Q",409);
        doAnswer(call->envelope(call.getArgument(1),call.getArgument(2),"NEW KEY")).when(remote).executeModel(anyString(),anyString(),any());send(c,"new","Q",200);
        assertThat(count("exploration_consultation_turn","consultation_id=? AND state='FAILED'",c)).isEqualTo(1);
    }
    @Test void onlyOneConcurrentQuestionCanInvokeTheModel()throws Exception{
        long c=open();var entered=new CountDownLatch(1);var finish=new CountDownLatch(1);ExecutorService pool=Executors.newSingleThreadExecutor();
        doAnswer(call->{entered.countDown();assertThat(finish.await(20,TimeUnit.SECONDS)).isTrue();return envelope(call.getArgument(1),call.getArgument(2),"ANSWER");}).when(remote).executeModel(anyString(),anyString(),any());
        Future<JsonNode> first=pool.submit(()->messages.send(OWNER,c,"one","Q"));
        try{assertThat(entered.await(20,TimeUnit.SECONDS)).isTrue();send(c,"two","Other",409);send(c,"one","Q",409);finish.countDown();assertThat(first.get(20,TimeUnit.SECONDS).path("assistantMessage").path("content").asText()).isEqualTo("ANSWER");}
        finally{finish.countDown();pool.shutdownNow();pool.awaitTermination(5,TimeUnit.SECONDS);}
        verify(remote,times(1)).executeModel(anyString(),anyString(),any());
    }
    @Test void cancellationDuringModelExecutionPreventsBothMessageWrites()throws Exception{
        long c=open();doAnswer(call->{jobs.command(OWNER,context(c),"CONSULTATION",call.getArgument(1),"cancel");return envelope(call.getArgument(1),call.getArgument(2),"LATE");}).when(remote).executeModel(anyString(),anyString(),any());
        send(c,"q","Q",409);assertThat(count("exploration_consultation_message","turn_id IN (SELECT id FROM exploration_consultation_turn WHERE consultation_id=?)",c)).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM exploration_generation_task WHERE mutation_key=?",String.class,mutation(c))).isEqualTo("CANCELLED");
    }
    @Test void withdrawalDuringModelExecutionPreventsLateRecreation()throws Exception{
        long c=open();doAnswer(call->{owners.addAll(jdbc.queryForList("SELECT owner_key FROM exploration_ai_owner WHERE user_id=?",String.class,OWNER));deletion.delete(OWNER);return envelope(call.getArgument(1),call.getArgument(2),"LATE");}).when(remote).executeModel(anyString(),anyString(),any());
        assertThatThrownBy(()->messages.send(OWNER,c,"q","Q")).isInstanceOf(RuntimeException.class);
        assertThat(count("exploration_consultation","id=?",c)).isZero();assertThat(count("exploration_generation_task","user_id=?",OWNER)).isZero();
    }
    @Test void insufficientApprovedEvidenceCreatesFixedAnswerWithoutModelInvocation()throws Exception{
        long c=open();when(remote.command(anyString(),anyString(),anyString(),any())).thenReturn(mapper.createObjectNode().put("status","INSUFFICIENT_EVIDENCE"));
        var answer=send(c,"q","Q",200);assertThat(answer.path("assistantMessage").path("evidenceStatus").asText()).isEqualTo("INSUFFICIENT");assertThat(answer.path("assistantMessage").path("sources").isEmpty()).isTrue();
        assertThat(send(c,"q","Q",200)).isEqualTo(answer);verify(remote,never()).executeModel(anyString(),anyString(),any());
    }
    @Test void secondMessageInsertFailureRollsBackGenerationResultAndBothMessages()throws Exception{
        long c=open();jdbc.execute("CREATE TRIGGER test_chat_publication_failure BEFORE INSERT ON exploration_consultation_message FOR EACH ROW BEGIN IF NEW.role='assistant' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SYNTHETIC publication failure'; END IF; END");
        try{assertThatThrownBy(()->messages.send(OWNER,c,"q","Q")).isInstanceOf(RuntimeException.class);}
        finally{jdbc.execute("DROP TRIGGER test_chat_publication_failure");}
        assertThat(count("exploration_consultation_message","turn_id IN (SELECT id FROM exploration_consultation_turn WHERE consultation_id=?)",c)).isZero();
        assertThat(jdbc.queryForObject("SELECT result_json IS NULL FROM exploration_generation_task WHERE mutation_key=?",Boolean.class,mutation(c))).isTrue();
        assertThat(jdbc.queryForObject("SELECT revision FROM exploration_consultation WHERE id=?",Long.class,c)).isZero();
    }
    @Test void damagedMessageCannotBeShownReplayedOrUsedAsFutureHistory()throws Exception{
        long c=open();var answer=send(c,"q","Q",200);jdbc.update("UPDATE exploration_consultation_message SET content='ALTERED' WHERE id=?",answer.path("assistantMessage").path("id").asLong());
        assertThat(send(c,"q","Q",503).path("code").asText()).isEqualTo("CONSULTATION_INTEGRITY_ERROR");send(c,"new","New",503);
        assertThatThrownBy(()->consultations.open(OWNER,jdbc.queryForObject("SELECT result_id FROM exploration_consultation WHERE id=?",Long.class,c),"resume")).isInstanceOf(RuntimeException.class);
        verify(remote,times(1)).executeModel(anyString(),anyString(),any());
    }
    @Test void completedPublicationIsImmutableAndCannotBeCancelledAsAnInflightJob()throws Exception{
        long c=open();send(c,"q","Q",200);
        assertThatThrownBy(()->jobs.command(OWNER,context(c),"CONSULTATION",mutation(c),"cancel")).isInstanceOf(RuntimeException.class);
        response(delete(path(c)+"/"+mutation(c)).header("Authorization",owner),409);
        assertThatThrownBy(()->jdbc.update("UPDATE exploration_consultation_turn SET reply_json=JSON_OBJECT() WHERE consultation_id=?",c)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        send(c,"q","Q",200);
    }
    @Test void reservationTransactionCannotLeaveATurnWithoutItsJob()throws Exception{
        long c=open();var draft=reservations.reserve(OWNER,c,"q","Q");
        assertThat(count("exploration_generation_task","user_id=? AND mutation_key=?",OWNER,draft.mutation())).isEqualTo(1);
        assertThatThrownBy(()->generations.reserve(OWNER,draft.context(),"CONSULTATION",draft.mutation(),draft.request())).isInstanceOf(RuntimeException.class);
        assertThat(generations.candidates(25)).noneMatch(t->t.mutation().equals(draft.mutation()));
    }
    @Test void failedJobReservationRollsBackTheTurnReservation()throws Exception{
        long c=open();jdbc.execute("CREATE TRIGGER test_chat_reservation_failure BEFORE INSERT ON exploration_generation_task FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SYNTHETIC reservation failure'");
        try{assertThatThrownBy(()->reservations.reserve(OWNER,c,"q","Q")).isInstanceOf(RuntimeException.class);}
        finally{jdbc.execute("DROP TRIGGER test_chat_reservation_failure");}
        assertThat(count("exploration_consultation_turn","consultation_id=?",c)).isZero();
    }
    @Test void expiredLeaseCannotPublishAndSameQuestionCanBeReclaimed()throws Exception{
        long c=open();doAnswer(call->{jdbc.update("UPDATE exploration_generation_task SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE mutation_key=?",(Object)call.getArgument(1));return envelope(call.getArgument(1),call.getArgument(2),"EXPIRED");}).when(remote).executeModel(anyString(),anyString(),any());
        send(c,"q","Q",409);assertThat(count("exploration_consultation_message","turn_id IN (SELECT id FROM exploration_consultation_turn WHERE consultation_id=?)",c)).isZero();
        doAnswer(call->envelope(call.getArgument(1),call.getArgument(2),"CURRENT")).when(remote).executeModel(anyString(),anyString(),any());
        assertThat(send(c,"q","Q",200).path("assistantMessage").path("content").asText()).isEqualTo("CURRENT");
    }
    @Test void threeTransientFailuresEndTheTurnWithoutFourthModelCall()throws Exception{
        long c=open();doThrow(new ExplorationJobClient.Unavailable()).when(remote).executeModel(anyString(),anyString(),any());
        for(int i=0;i<3;i++){if(i>0)jdbc.update("UPDATE exploration_generation_task SET next_attempt_at=UTC_TIMESTAMP(6) WHERE mutation_key=?",mutation(c));send(c,"q","Q",503);}
        send(c,"q","Q",409);verify(remote,times(3)).executeModel(anyString(),anyString(),any());
        assertThat(jdbc.queryForObject("SELECT state FROM exploration_consultation_turn WHERE consultation_id=?",String.class,c)).isEqualTo("FAILED");
    }
    @Test void declinedModelOutputBecomesFixedUngroundedGuidance()throws Exception{
        long c=open();doAnswer(call->{var e=envelope(call.getArgument(1),call.getArgument(2),"UNUSED");((ObjectNode)e.path("response")).put("status","DECLINED").put("reasonCode","OUT_OF_SCOPE").putNull("result");return e;}).when(remote).executeModel(anyString(),anyString(),any());
        var answer=send(c,"q","Q",200).path("assistantMessage");assertThat(answer.path("evidenceStatus").asText()).isEqualTo("INSUFFICIENT");assertThat(answer.path("sources").isEmpty()).isTrue();
    }
    @Test void reconnectProvidesAnOwnedRetryKeyWithoutRequiringLostClientKey()throws Exception{
        long c=open();doThrow(new ExplorationJobClient.Unavailable()).when(remote).executeModel(anyString(),anyString(),any());send(c,"original-key","Remember this question",503);
        long result=jdbc.queryForObject("SELECT result_id FROM exploration_consultation WHERE id=?",Long.class,c);
        var resume=consultations.open(OWNER,result,"resume");assertThat(resume.messages()).isEmpty();assertThat(resume.pendingRequest().status()).isEqualTo("QUEUED");
        assertThat(resume.pendingRequest().content()).isEqualTo("Remember this question");assertThat(resume.pendingRequest().nextRetryAt()).endsWith("Z");
        send(c,resume.pendingRequest().retryKey(),"Different question",409);
        jdbc.update("UPDATE exploration_generation_task SET next_attempt_at=UTC_TIMESTAMP(6) WHERE mutation_key=?",resume.pendingRequest().retryKey());
        doAnswer(call->envelope(call.getArgument(1),call.getArgument(2),"RECOVERED")).when(remote).executeModel(anyString(),anyString(),any());
        var recovered=send(c,resume.pendingRequest().retryKey(),resume.pendingRequest().content(),200);
        assertThat(send(c,"original-key","Remember this question",200)).isEqualTo(recovered);
        assertThat(count("exploration_consultation_turn","consultation_id=?",c)).isEqualTo(1);
    }
    @Test void ownerCanCancelPendingRequestEvenWhenRemoteCancellationIsUnavailable()throws Exception{
        long c=open();var draft=reservations.reserve(OWNER,c,"q","Q");String cancel=path(c)+"/"+draft.mutation();
        response(delete(cancel).header("Authorization",other),404);
        when(remote.command(eq("cancel"),anyString(),anyString(),any())).thenThrow(new ExplorationJobClient.Unavailable());
        response(delete(cancel).header("Authorization",owner),200);response(delete(cancel).header("Authorization",owner),200);
        assertThat(jdbc.queryForObject("SELECT state FROM exploration_generation_task WHERE mutation_key=?",String.class,draft.mutation())).isEqualTo("CANCELLED");
        send(c,"q","Q",409);send(c,"new","Another question",200);
    }
    @Test void threeAbandonedExpiredLeasesReleaseThePendingSlotForANewQuestion()throws Exception{
        long c=open();var draft=reservations.reserve(OWNER,c,"abandoned","Q");
        for(int i=0;i<3;i++){generations.claimModel(OWNER,draft.context(),"CONSULTATION",draft.mutation());jdbc.update("UPDATE exploration_generation_task SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE mutation_key=?",draft.mutation());}
        send(c,"new","New question",200);
        assertThat(jdbc.queryForObject("SELECT failure_code FROM exploration_generation_task WHERE mutation_key=?",String.class,draft.mutation())).isEqualTo("ATTEMPTS_EXHAUSTED");
    }
}
