package com.jinroon.jobe.domain.exploration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.ai.*;
import com.jinroon.jobe.domain.exploration.context.ResultContextService;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

@TestPropertySource(properties="exploration.jobs.dispatch.initial-delay-ms=86400000")
class MockDispatchIntegrationTest extends CompletedExplorationTestSupport {
    @Autowired OwnedGenerationStore store;
    @Autowired OwnedMockExecutionService execution;
    @Autowired ExplorationModelAvailability availability;
    @Autowired MockGenerationDispatcher dispatch;
    @Autowired ExplorationJobService jobs;
    @Autowired ResultContextService contexts;
    @Autowired ExplorationAiContract contract;
    @MockitoBean ExplorationJobClient remote;
    Set<String> owners=new HashSet<>();
    @BeforeEach void configure(){
        ReflectionTestUtils.setField(execution,"enabled",true);ReflectionTestUtils.setField(availability,"erasureEnabled",true);ReflectionTestUtils.setField(dispatch,"enabled",true);
        when(remote.readyForMock()).thenReturn(true);
        when(remote.command(anyString(),anyString(),anyString(),any())).thenReturn(mapper.createObjectNode().put("status","QUEUED"));
        when(remote.executeMock(anyString(),anyString(),any())).thenAnswer(call->envelope(call.getArgument(2)).put("mutationKey",call.<String>getArgument(1)));
    }
    @Override @AfterEach void cleanup(){
        owners.addAll(jdbc.queryForList("SELECT owner_key FROM exploration_ai_owner WHERE user_id=?",String.class,OWNER));super.cleanup();for(String owner:owners)jdbc.update("DELETE FROM exploration_ai_erasure_outbox WHERE owner_key=?",owner);
        ReflectionTestUtils.setField(dispatch,"enabled",false);ReflectionTestUtils.setField(execution,"enabled",false);ReflectionTestUtils.setField(availability,"erasureEnabled",false);
    }
    long enqueue()throws Exception{long context=contexts.prepare(OWNER,completedResult(true),"context","CONSULTATION",0,null).body().path("contextId").asLong();jobs.command(OWNER,context,"CONSULTATION","m","enqueue");clearInvocations(remote);return context;}
    ObjectNode envelope(JsonNode original){
        ObjectNode bound=original.deepCopy();var ev=(ObjectNode)bound.path("evidence");ev.put("bindingStatus","BOUND").put("datasetVersion","TEST_DISPATCH");var refs=ev.putArray("referenceKeys");for(int i=0;i<bound.path("input").path("candidateNames").size();i++)refs.add("ref_"+String.format("%064x",i));bound.put("inputHash",contract.inputHash(bound));
        var response=mapper.createObjectNode();for(String key:List.of("contractVersion","requestId","inputHash","purpose"))response.set(key,bound.path(key));response.put("status","SUCCEEDED").put("changesRecommendation",false).putNull("reasonCode");var result=response.putObject("result").put("summary","[MOCK] dispatched result");result.putArray("questions");result.set("referenceKeys",refs.deepCopy());
        var out=mapper.createObjectNode().put("transportVersion","EXPLORATION_MOCK_EXECUTION_V1_0").put("mode","MOCK").put("generationAuthorized",false).put("mutationKey","m").put("originalInputHash",original.path("inputHash").asText()).put("sourceContextHash",original.path("sourceContextHash").asText()).put("bindingHash","a".repeat(64)).put("jobId",1);out.set("boundRequest",bound);out.set("response",response);return out;
    }
    Map<String,Object> row(){return jdbc.queryForMap("SELECT * FROM exploration_generation_task WHERE user_id=? AND mutation_key='m'",OWNER);}
    void due(){jdbc.update("UPDATE exploration_generation_task SET next_attempt_at=UTC_TIMESTAMP(6) WHERE user_id=? AND mutation_key='m'",OWNER);}
    void expire(){jdbc.update("UPDATE exploration_generation_task SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE user_id=? AND mutation_key='m'",OWNER);}
    @Test void scheduledBatchCompletesOnceAndDoesNotReselectResult()throws Exception{enqueue();dispatch.scheduledDrain();assertThat(row().get("state")).isEqualTo("MOCK_STORED");assertThat(dispatch.drain()).isZero();verify(remote,times(1)).executeMock(anyString(),anyString(),any());}
    @Test void transientFailureWaitsThenRecoversWithoutImmediateRetry()throws Exception{
        enqueue();doThrow(new ExplorationJobClient.Unavailable()).doAnswer(call->envelope(call.getArgument(2))).when(remote).executeMock(anyString(),anyString(),any());
        assertThat(dispatch.drain()).isEqualTo(1);assertThat(row().get("state")).isEqualTo("QUEUED");assertThat(row().get("failure_code")).isEqualTo("TRANSIENT");assertThat(row().get("next_attempt_at")).isNotNull();assertThat(dispatch.drain()).isZero();
        due();assertThat(dispatch.drain()).isEqualTo(1);assertThat(row().get("state")).isEqualTo("MOCK_STORED");assertThat(((Number)row().get("attempt")).intValue()).isEqualTo(2);verify(remote,times(2)).executeMock(anyString(),anyString(),any());
    }
    @Test void repeatedTransientFailureStopsAtThreeAttempts()throws Exception{
        enqueue();doThrow(new ExplorationJobClient.Unavailable()).when(remote).executeMock(anyString(),anyString(),any());
        for(int n=0;n<3;n++){due();dispatch.drain();}
        assertThat(row().get("state")).isEqualTo("FAILED");assertThat(row().get("failure_code")).isEqualTo("ATTEMPTS_EXHAUSTED");assertThat(dispatch.drain()).isZero();verify(remote,times(3)).executeMock(anyString(),anyString(),any());
    }
    @Test void authenticationFailureIsPermanentAndBodyIsNeverStored()throws Exception{
        enqueue();doThrow(new ExplorationJobClient.Unavailable(ExplorationJobClient.Failure.AUTHENTICATION)).when(remote).executeMock(anyString(),anyString(),any());dispatch.drain();assertThat(row().get("state")).isEqualTo("FAILED");assertThat(row().get("failure_code")).isEqualTo("AUTHENTICATION");assertThat(row().get("result_json")).isNull();assertThat(dispatch.drain()).isZero();
    }
    @Test void missingApprovedEvidenceNeverComputesAndDoesNotLoop()throws Exception{
        enqueue();when(remote.command(eq("enqueue"),anyString(),anyString(),any())).thenReturn(mapper.createObjectNode().put("status","INSUFFICIENT_EVIDENCE"));dispatch.drain();assertThat(row().get("state")).isEqualTo("FAILED");assertThat(row().get("failure_code")).isEqualTo("EVIDENCE_UNAVAILABLE");verify(remote,never()).executeMock(anyString(),anyString(),any());
    }
    @Test void cancelledTaskIsNeverSelected()throws Exception{long c=enqueue();store.cancel(OWNER,c,"CONSULTATION","m");assertThat(dispatch.drain()).isZero();verify(remote,never()).executeMock(anyString(),anyString(),any());}
    @Test void withdrawnAccountIsNeverSelected()throws Exception{enqueue();owners.addAll(jdbc.queryForList("SELECT owner_key FROM exploration_ai_owner WHERE user_id=?",String.class,OWNER));deletion.delete(OWNER);assertThat(dispatch.drain()).isZero();verify(remote,never()).executeMock(anyString(),anyString(),any());}
    @Test void expiredWorkerIsReclaimedAndCanComplete()throws Exception{long c=enqueue();store.claim(OWNER,c,"CONSULTATION","m");expire();dispatch.drain();assertThat(row().get("state")).isEqualTo("MOCK_STORED");assertThat(((Number)row().get("attempt")).intValue()).isEqualTo(2);}
    @Test void expiredThirdAttemptIsFinalizedWithoutFourthExecution()throws Exception{long c=enqueue();for(int i=0;i<3;i++){store.claim(OWNER,c,"CONSULTATION","m");expire();}assertThat(dispatch.drain()).isZero();assertThat(row().get("state")).isEqualTo("FAILED");assertThat(row().get("failure_code")).isEqualTo("ATTEMPTS_EXHAUSTED");verify(remote,never()).executeMock(anyString(),anyString(),any());}
    @Test void staleFailureCannotReplaceNewLease()throws Exception{long c=enqueue();var old=store.claim(OWNER,c,"CONSULTATION","m");expire();var current=store.claim(OWNER,c,"CONSULTATION","m");store.failed(new OwnedGenerationStore.Candidate(OWNER,c,"CONSULTATION","m"),old.token(),true,"TRANSIENT");assertThat(row().get("state")).isEqualTo("RUNNING");assertThat(row().get("lease_token")).isEqualTo(current.token());assertThat(row().get("next_attempt_at")).isNull();}
    @Test void onlyOneOfTwoContendingWorkersComputes()throws Exception{
        enqueue();var task=store.candidates(10).stream().filter(c->c.user()==OWNER).findFirst().orElseThrow();
        var outcomes=parallel(()->{try{execution.executeQueued(task);return "STORED";}catch(RuntimeException e){return "BUSY";}});
        assertThat(outcomes).containsExactlyInAnyOrder("STORED","BUSY");verify(remote,times(1)).executeMock(anyString(),anyString(),any());assertThat(row().get("state")).isEqualTo("MOCK_STORED");
    }
    @Test void activeWorkerIsNotReselectedByAnotherDispatcher()throws Exception{long c=enqueue();store.claim(OWNER,c,"CONSULTATION","m");assertThat(dispatch.drain()).isZero();verify(remote,never()).executeMock(anyString(),anyString(),any());}
    @Test void disabledDispatcherOrMissingErasureDeliveryDoesNotClaim()throws Exception{
        enqueue();ReflectionTestUtils.setField(dispatch,"enabled",false);assertThat(dispatch.drain()).isZero();ReflectionTestUtils.setField(dispatch,"enabled",true);ReflectionTestUtils.setField(availability,"erasureEnabled",false);assertThat(dispatch.drain()).isZero();assertThat(((Number)row().get("attempt")).intValue()).isZero();verify(remote,never()).executeMock(anyString(),anyString(),any());
    }
    @Test void ownerReadsRetryAndFinalStateWithoutDependingOnUpstream()throws Exception{
        long c=enqueue();doThrow(new ExplorationJobClient.Unavailable()).when(remote).executeMock(anyString(),anyString(),any());dispatch.drain();
        clearInvocations(remote);var status=jobs.command(OWNER,c,"CONSULTATION","m","read");assertThat(status.path("statusVersion").asText()).isEqualTo("OWNED_MOCK_TASK_STATUS_V1_0");assertThat(status.path("status").asText()).isEqualTo("QUEUED");assertThat(status.path("failureCode").asText()).isEqualTo("TRANSIENT");assertThat(status.path("nextRetryAt").asText()).endsWith("Z");verifyNoInteractions(remote);
        due();dispatch.drain();due();dispatch.drain();clearInvocations(remote);assertThat(jobs.command(OWNER,c,"CONSULTATION","m","read").path("status").asText()).isEqualTo("FAILED");verifyNoInteractions(remote);
    }
    @Test void suspendedAccountFailsBeforeAnyRemoteCall()throws Exception{
        enqueue();jdbc.update("UPDATE users SET status='suspended' WHERE id=?",OWNER);
        try{dispatch.drain();assertThat(row().get("state")).isEqualTo("FAILED");assertThat(row().get("failure_code")).isEqualTo("SOURCE_UNAVAILABLE");verify(remote,never()).executeMock(anyString(),anyString(),any());}
        finally{jdbc.update("UPDATE users SET status='active' WHERE id=?",OWNER);}
    }
    @Test void invalidSealedSourceFailsWithoutComputing()throws Exception{
        long c=enqueue();long rid=jdbc.queryForObject("SELECT result_id FROM exploration_result_context WHERE id=?",Long.class,c);String original=jdbc.queryForObject("SELECT result_json FROM exploration_result WHERE id=?",String.class,rid);
        jdbc.update("UPDATE exploration_result SET result_json=JSON_SET(result_json,'$.status','BAD') WHERE id=?",rid);
        try{dispatch.drain();assertThat(row().get("state")).isEqualTo("FAILED");verify(remote,never()).executeMock(anyString(),anyString(),any());}
        finally{jdbc.update("UPDATE exploration_result SET result_json=? WHERE id=?",original,rid);}
    }
    @Test void invalidOutputIsPermanentAndNeverStored()throws Exception{
        enqueue();doAnswer(call->{var value=envelope(call.getArgument(2));((ObjectNode)value.path("response")).put("inputHash","0".repeat(64));return value;}).when(remote).executeMock(anyString(),anyString(),any());
        dispatch.drain();assertThat(row().get("state")).isEqualTo("FAILED");assertThat(row().get("failure_code")).isEqualTo("OUTPUT_INVALID");assertThat(row().get("result_json")).isNull();assertThat(dispatch.drain()).isZero();
    }
    @Test void eachDrainIsBoundedToTenTasks()throws Exception{
        long c=contexts.prepare(OWNER,completedResult(true),"batch-context","CONSULTATION",0,null).body().path("contextId").asLong();for(int n=0;n<11;n++)jobs.command(OWNER,c,"CONSULTATION","batch"+n,"enqueue");
        assertThat(dispatch.drain()).isEqualTo(10);assertThat(count("exploration_generation_task","user_id=? AND state='MOCK_STORED'",OWNER)).isEqualTo(10);
        assertThat(dispatch.drain()).isEqualTo(1);assertThat(count("exploration_generation_task","user_id=? AND state='MOCK_STORED'",OWNER)).isEqualTo(11);
    }
    @Test void databaseResourceFailureIsRetryableWithoutPersistingExceptionText()throws Exception{
        enqueue();doThrow(new org.springframework.dao.DataAccessResourceFailureException("PRIVATE_DATABASE_DETAIL")).when(remote).executeMock(anyString(),anyString(),any());dispatch.drain();
        assertThat(row().get("state")).isEqualTo("QUEUED");assertThat(row().get("failure_code")).isEqualTo("TRANSIENT");assertThat(row().get("next_attempt_at")).isNotNull();assertThat(row().get("result_json")).isNull();
    }
}
