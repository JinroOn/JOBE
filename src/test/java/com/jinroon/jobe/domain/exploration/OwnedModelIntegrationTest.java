package com.jinroon.jobe.domain.exploration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.ai.*;
import com.jinroon.jobe.domain.exploration.context.ResultContextService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

class OwnedModelIntegrationTest extends CompletedExplorationTestSupport {
    @Autowired OwnedGenerationStore store;
    @Autowired OwnedMockExecutionService execution;
    @Autowired ExplorationModelAvailability availability;
    @Autowired ExplorationJobService jobs;
    @Autowired ResultContextService contexts;
    @Autowired ExplorationAiContract contract;
    @MockitoBean ExplorationJobClient remote;
    Set<String> owners=new HashSet<>();
    @BeforeEach void configure(){ReflectionTestUtils.setField(availability,"modelEnabled",true);ReflectionTestUtils.setField(availability,"erasureEnabled",true);when(remote.command(anyString(),anyString(),anyString(),any())).thenReturn(mapper.createObjectNode().put("status","QUEUED"));when(remote.executeModel(anyString(),anyString(),any())).thenAnswer(call->modelEnvelope(call.getArgument(2)));}
    @Override @AfterEach void cleanup(){owners.addAll(jdbc.queryForList("SELECT owner_key FROM exploration_ai_owner WHERE user_id=?",String.class,OWNER));super.cleanup();for(String owner:owners)jdbc.update("DELETE FROM exploration_ai_erasure_outbox WHERE owner_key=?",owner);ReflectionTestUtils.setField(availability,"modelEnabled",false);ReflectionTestUtils.setField(availability,"erasureEnabled",false);}
    long context()throws Exception{return contexts.prepare(OWNER,completedResult(true),"context","CONSULTATION",0,null).body().path("contextId").asLong();}
    ObjectNode modelEnvelope(JsonNode original){
        ObjectNode bound=original.deepCopy();var ev=(ObjectNode)bound.path("evidence");ev.put("bindingStatus","BOUND").put("datasetVersion","TEST_MODEL_DATA");var refs=ev.putArray("referenceKeys");for(int i=0;i<bound.path("input").path("candidateNames").size();i++)refs.add("ref_"+String.format("%064x",i));bound.put("inputHash",contract.inputHash(bound));
        var response=mapper.createObjectNode();for(String key:List.of("contractVersion","requestId","inputHash","purpose"))response.set(key,bound.path(key));response.put("status","SUCCEEDED").put("changesRecommendation",false).putNull("reasonCode");var result=response.putObject("result").put("summary","SYNTHETIC MODEL OUTPUT");result.putArray("questions");result.set("referenceKeys",refs.deepCopy());
        var out=mapper.createObjectNode().put("transportVersion","EXPLORATION_MODEL_EXECUTION_V1_0").put("mode","MODEL").put("generationAuthorized",true).put("mutationKey","m").put("originalInputHash",original.path("inputHash").asText()).put("sourceContextHash",original.path("sourceContextHash").asText()).put("bindingHash","a".repeat(64)).put("jobId",1);out.set("boundRequest",bound);out.set("response",response);out.putObject("providerMetadata").put("provider","openai").put("model","TEST_MODEL").put("promptVersion","EXPLORATION_MODEL_V1_0");return out;
    }
    @Test void storesModelResultAndReplaysWithoutCallingProviderAgain()throws Exception{long c=context();var result=execution.executeModel(OWNER,c,"CONSULTATION","m");assertThat(result.path("status").asText()).isEqualTo("MODEL_STORED");assertThat(result.path("mode").asText()).isEqualTo("MODEL");assertThat(execution.executeModel(OWNER,c,"CONSULTATION","m")).isEqualTo(result);verify(remote,times(1)).executeModel(anyString(),anyString(),any());assertThat(store.candidates(25)).isEmpty();}
    @Test void mockAndModelKeysCannotBeReinterpreted()throws Exception{long c=context();jobs.command(OWNER,c,"CONSULTATION","m","enqueue");assertThatThrownBy(()->execution.executeModel(OWNER,c,"CONSULTATION","m")).isInstanceOf(RuntimeException.class);verify(remote,never()).executeModel(anyString(),anyString(),any());}
    @Test void modelQueueIsExcludedFromMockDispatcherAfterTransientFailure()throws Exception{long c=context();doThrow(new ExplorationJobClient.Unavailable()).when(remote).executeModel(anyString(),anyString(),any());assertThatThrownBy(()->execution.executeModel(OWNER,c,"CONSULTATION","m")).isInstanceOf(RuntimeException.class);assertThat(store.candidates(25)).isEmpty();assertThat(jdbc.queryForObject("SELECT execution_mode FROM exploration_generation_task WHERE user_id=?",String.class,OWNER)).isEqualTo("MODEL");}
    @Test void cancellationWhileModelRunsPreventsStorage()throws Exception{long c=context();doAnswer(call->{jobs.command(OWNER,c,"CONSULTATION","m","cancel");return modelEnvelope(call.getArgument(2));}).when(remote).executeModel(anyString(),anyString(),any());assertThatThrownBy(()->execution.executeModel(OWNER,c,"CONSULTATION","m")).isInstanceOf(RuntimeException.class);assertThat(jdbc.queryForObject("SELECT state FROM exploration_generation_task WHERE user_id=?",String.class,OWNER)).isEqualTo("CANCELLED");assertThat(jdbc.queryForObject("SELECT result_json IS NULL FROM exploration_generation_task WHERE user_id=?",Boolean.class,OWNER)).isTrue();}
    @Test void withdrawalWhileModelRunsPreventsStorage()throws Exception{long c=context();doAnswer(call->{owners.addAll(jdbc.queryForList("SELECT owner_key FROM exploration_ai_owner WHERE user_id=?",String.class,OWNER));deletion.delete(OWNER);return modelEnvelope(call.getArgument(2));}).when(remote).executeModel(anyString(),anyString(),any());assertThatThrownBy(()->execution.executeModel(OWNER,c,"CONSULTATION","m")).isInstanceOf(RuntimeException.class);assertThat(count("exploration_generation_task","user_id=?",OWNER)).isZero();}
    @Test void explicitHttpModelPathIsOwnedAndDisabledByDefault()throws Exception{long c=context();String path="/api/exploration/v1/contexts/"+c+"/jobs/CONSULTATION/m/model-run";ReflectionTestUtils.setField(availability,"modelEnabled",false);response(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Authorization",owner),503);ReflectionTestUtils.setField(availability,"modelEnabled",true);response(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Authorization",other),404);var reply=response(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Authorization",owner),200);assertThat(reply.path("mode").asText()).isEqualTo("MODEL");}
    @Test void databaseRejectsChangingModelModeOrMislabelingStoredOutput()throws Exception{long c=context();execution.executeModel(OWNER,c,"CONSULTATION","m");assertThatThrownBy(()->jdbc.update("UPDATE exploration_generation_task SET execution_mode='MOCK' WHERE user_id=?",OWNER)).isInstanceOf(org.springframework.dao.DataAccessException.class);assertThatThrownBy(()->jdbc.update("UPDATE exploration_generation_task SET state='MOCK_STORED' WHERE user_id=?",OWNER)).isInstanceOf(org.springframework.dao.DataAccessException.class);}
}
