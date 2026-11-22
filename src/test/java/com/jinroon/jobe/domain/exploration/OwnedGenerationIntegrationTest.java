package com.jinroon.jobe.domain.exploration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.ai.*;
import com.jinroon.jobe.domain.exploration.context.ResultContextService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class OwnedGenerationIntegrationTest extends CompletedExplorationTestSupport {
    @Autowired ExplorationJobService jobs;
    @Autowired OwnedGenerationStore store;
    @Autowired OwnedMockExecutionService executor;
    @Autowired ResultContextService contexts;
    @Autowired ExplorationAiContract contract;
    @Autowired com.jinroon.jobe.domain.exploration.support.PostResultSupportService support;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean ExplorationJobClient remote;
    final Set<String> owners=new HashSet<>();
    @BeforeEach void configure(){ReflectionTestUtils.setField(executor,"enabled",true);when(remote.command(anyString(),anyString(),anyString(),any())).thenReturn(mapper.createObjectNode().put("status","QUEUED"));}
    @Override @AfterEach void cleanup(){
        owners.addAll(jdbc.queryForList("SELECT owner_key FROM exploration_ai_owner WHERE user_id IN (?,?)",String.class,OWNER,OTHER));
        super.cleanup();for(String owner:owners)jdbc.update("DELETE FROM exploration_ai_erasure_outbox WHERE owner_key=?",owner);
        ReflectionTestUtils.setField(executor,"enabled",false);
    }
    long context()throws Exception{return contexts.prepare(OWNER,completedResult(true),UUID.randomUUID().toString(),"CONSULTATION",0,null).body().path("contextId").asLong();}
    long enqueue()throws Exception{long c=context();jobs.command(OWNER,c,"CONSULTATION","m","enqueue");return c;}
    ObjectNode envelope(JsonNode original){
        ObjectNode bound=original.deepCopy();var evidence=(ObjectNode)bound.path("evidence");evidence.put("bindingStatus","BOUND").put("datasetVersion","TEST_OWNED_V1");var refs=evidence.putArray("referenceKeys");
        int subjects=bound.path("input").path("targetName").isNull()?bound.path("input").path("candidateNames").size():1;
        for(int i=0;i<subjects;i++)refs.add("ref_"+String.format("%064x",i));bound.put("inputHash",contract.inputHash(bound));
        var response=mapper.createObjectNode();for(String key:List.of("contractVersion","requestId","inputHash","purpose"))response.set(key,bound.path(key));
        response.put("status","SUCCEEDED").put("changesRecommendation",false).putNull("reasonCode");var result=response.putObject("result").put("summary","[MOCK] owned result");result.putArray("questions");result.set("referenceKeys",refs.deepCopy());
        if(original.path("purpose").asText().equals("PLAN")){
            var plan=response.putObject("result").put("targetName",bound.path("input").path("targetName").asText()).put("overview","[MOCK] owned plan");
            var step=plan.putArray("steps").addObject().put("title","[MOCK] step").put("description","Synthetic plan step");step.set("referenceKeys",refs.deepCopy());
        }
        var output=mapper.createObjectNode().put("transportVersion","EXPLORATION_MOCK_EXECUTION_V1_0").put("mode","MOCK").put("generationAuthorized",false).put("mutationKey","m").put("originalInputHash",original.path("inputHash").asText()).put("sourceContextHash",original.path("sourceContextHash").asText()).put("jobId",1).put("bindingHash","a".repeat(64));output.set("boundRequest",bound);output.set("response",response);return output;
    }
    String state(){return jdbc.queryForObject("SELECT state FROM exploration_generation_task WHERE user_id=? AND mutation_key='m'",String.class,OWNER);}
    void noResult(){assertThat(jdbc.queryForObject("SELECT result_json IS NULL FROM exploration_generation_task WHERE user_id=? AND mutation_key='m'",Boolean.class,OWNER)).isTrue();}
    @Test void ownsAndPersistsMockResultAndReplayDoesNotRegenerate()throws Exception{
        long c=enqueue();when(remote.executeMock(anyString(),eq("m"),any())).thenAnswer(call->envelope(call.getArgument(2)));
        var response=executor.execute(OWNER,c,"CONSULTATION","m");assertThat(response.path("status").asText()).isEqualTo("MOCK_STORED");assertThat(response.path("generationAuthorized").asBoolean()).isFalse();assertThat(state()).isEqualTo("MOCK_STORED");
        assertThat(executor.execute(OWNER,c,"CONSULTATION","m")).isEqualTo(response);verify(remote,times(1)).executeMock(anyString(),eq("m"),any());
    }
    @Test void foreignOwnerIsRejectedBeforeRemoteComputation()throws Exception{
        long c=enqueue();clearInvocations(remote);assertThatThrownBy(()->executor.execute(OTHER,c,"CONSULTATION","m")).isInstanceOf(RuntimeException.class);verifyNoInteractions(remote);noResult();
    }
    @Test void cancelDuringComputationWinsEvenIfRemoteCancelFails()throws Exception{
        long c=enqueue();when(remote.executeMock(anyString(),anyString(),any())).thenAnswer(call->{doThrow(new ExplorationJobClient.Unavailable()).when(remote).command(eq("cancel"),anyString(),anyString(),any());assertThatThrownBy(()->jobs.command(OWNER,c,"CONSULTATION","m","cancel")).isInstanceOf(ExplorationJobClient.Unavailable.class);return envelope(call.getArgument(2));});
        assertThatThrownBy(()->executor.execute(OWNER,c,"CONSULTATION","m")).isInstanceOf(RuntimeException.class);assertThat(state()).isEqualTo("CANCELLED");noResult();
    }
    @Test void withdrawalDuringComputationPreventsResultAndCascadesTask()throws Exception{
        long c=enqueue();owners.addAll(jdbc.queryForList("SELECT owner_key FROM exploration_ai_owner WHERE user_id=?",String.class,OWNER));
        when(remote.executeMock(anyString(),anyString(),any())).thenAnswer(call->{deletion.delete(OWNER);return envelope(call.getArgument(2));});
        assertThatThrownBy(()->executor.execute(OWNER,c,"CONSULTATION","m")).isInstanceOf(RuntimeException.class);assertThat(count("exploration_generation_task","user_id=?",OWNER)).isZero();
    }
    @Test void expiryDuringComputationPreventsResult()throws Exception{
        long c=enqueue();when(remote.executeMock(anyString(),anyString(),any())).thenAnswer(call->{jdbc.update("UPDATE exploration_generation_task SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE user_id=?",OWNER);return envelope(call.getArgument(2));});
        assertThatThrownBy(()->executor.execute(OWNER,c,"CONSULTATION","m")).isInstanceOf(RuntimeException.class);noResult();
    }
    @Test void sealTamperedDuringComputationPreventsResultAndOriginalIsRestored()throws Exception{
        long c=enqueue();long result=jdbc.queryForObject("SELECT result_id FROM exploration_result_context WHERE id=?",Long.class,c);String original=jdbc.queryForObject("SELECT result_json FROM exploration_result WHERE id=?",String.class,result);
        when(remote.executeMock(anyString(),anyString(),any())).thenAnswer(call->{jdbc.update("UPDATE exploration_result SET result_json=JSON_SET(result_json,'$.status','BAD') WHERE id=?",result);return envelope(call.getArgument(2));});
        try{assertThatThrownBy(()->executor.execute(OWNER,c,"CONSULTATION","m")).isInstanceOf(RuntimeException.class);noResult();}finally{jdbc.update("UPDATE exploration_result SET result_json=? WHERE id=?",original,result);}
    }
    @Test void invalidOutputDoesNotPersist()throws Exception{
        long c=enqueue();var lease=store.claim(OWNER,c,"CONSULTATION","m");var output=envelope(lease.request());((ObjectNode)output.path("response")).put("inputHash","0".repeat(64));
        assertThatThrownBy(()->store.completeMock(OWNER,c,"CONSULTATION","m",lease.token(),output)).isInstanceOf(RuntimeException.class);noResult();assertThat(state()).isEqualTo("RUNNING");
    }
    @Test void expiredLeaseReclaimRejectsOldToken()throws Exception{
        long c=enqueue();var first=store.claim(OWNER,c,"CONSULTATION","m");jdbc.update("UPDATE exploration_generation_task SET lease_until=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE user_id=?",OWNER);var second=store.claim(OWNER,c,"CONSULTATION","m");
        assertThatThrownBy(()->store.completeMock(OWNER,c,"CONSULTATION","m",first.token(),envelope(first.request()))).isInstanceOf(RuntimeException.class);noResult();store.completeMock(OWNER,c,"CONSULTATION","m",second.token(),envelope(second.request()));assertThat(state()).isEqualTo("MOCK_STORED");
    }
    @Test void finalTransactionRollbackRemovesBothOutputAndCompletion()throws Exception{
        long c=enqueue();var lease=store.claim(OWNER,c,"CONSULTATION","m");
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->{store.completeMock(OWNER,c,"CONSULTATION","m",lease.token(),envelope(lease.request()));throw new IllegalStateException("SYNTHETIC_ROLLBACK");})).isInstanceOf(IllegalStateException.class);assertThat(state()).isEqualTo("RUNNING");noResult();
    }
    @Test void cancelAfterStoredResultErasesUserOutput()throws Exception{
        long c=enqueue();var lease=store.claim(OWNER,c,"CONSULTATION","m");store.completeMock(OWNER,c,"CONSULTATION","m",lease.token(),envelope(lease.request()));store.cancel(OWNER,c,"CONSULTATION","m");assertThat(state()).isEqualTo("CANCELLED");noResult();
    }
    @Test void explicitHttpMockRunIsOwnedAndNeverClaimsRealGeneration()throws Exception{
        long c=enqueue();when(remote.executeMock(anyString(),eq("m"),any())).thenAnswer(call->envelope(call.getArgument(2)));
        String path="/api/exploration/v1/contexts/"+c+"/jobs/CONSULTATION/m/mock-run";
        var result=response(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Authorization",owner),200);assertThat(result.path("mode").asText()).isEqualTo("MOCK");assertThat(result.path("generationAuthorized").asBoolean()).isFalse();
        clearInvocations(remote);response(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Authorization",other),404);verifyNoInteractions(remote);
        response(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path),401);
    }
    @Test void planStoresOnlyTheSelectedOwnedTarget()throws Exception{
        long result=completedResult(true);String target=mapper.readTree(jdbc.queryForObject("SELECT result_json FROM exploration_result WHERE id=?",String.class,result)).path("rankedCandidates").get(0).path("majorCode").asText();
        support.select(OWNER,result,"select",0,"PLAN",target);
        long c=contexts.prepare(OWNER,result,"plan-context","PLAN",1,target).body().path("contextId").asLong();
        when(remote.executeMock(anyString(),eq("m"),any())).thenAnswer(call->envelope(call.getArgument(2)));
        var saved=executor.execute(OWNER,c,"PLAN","m");assertThat(saved.path("response").path("purpose").asText()).isEqualTo("PLAN");assertThat(saved.path("response").path("result").path("targetName").asText()).isNotBlank();assertThat(state()).isEqualTo("MOCK_STORED");
    }
    @Test void httpPersistenceContextCannotHideAResultChangedDuringNetworkCall()throws Exception{
        long c=enqueue();long rid=jdbc.queryForObject("SELECT result_id FROM exploration_result_context WHERE id=?",Long.class,c);String original=jdbc.queryForObject("SELECT result_json FROM exploration_result WHERE id=?",String.class,rid);
        when(remote.executeMock(anyString(),anyString(),any())).thenAnswer(call->{jdbc.update("UPDATE exploration_result SET result_json=JSON_SET(result_json,'$.status','BAD') WHERE id=?",rid);return envelope(call.getArgument(2));});
        try{response(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/exploration/v1/contexts/"+c+"/jobs/CONSULTATION/m/mock-run").header("Authorization",owner),503);noResult();}
        finally{jdbc.update("UPDATE exploration_result SET result_json=? WHERE id=?",original,rid);}
    }
}
