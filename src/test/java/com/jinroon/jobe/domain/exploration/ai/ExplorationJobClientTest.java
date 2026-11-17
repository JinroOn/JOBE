package com.jinroon.jobe.domain.exploration.ai;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import java.nio.file.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

class ExplorationJobClientTest {
    ObjectMapper mapper=new ObjectMapper();RestTemplate rest=new RestTemplate();
    ExplorationJobClient client=new ExplorationJobClient(rest,mapper,new ExplorationAiContract(new ExplorationJson(mapper)));
    MockRestServiceServer server=MockRestServiceServer.bindTo(rest).build();JsonNode request;
    @BeforeEach void setup()throws Exception{
        request=mapper.readTree(Files.readString(Path.of("datasets/exploration-ai-contract/cases.json"))).path("adapterCases").get(0).path("expectedRequest");
        ReflectionTestUtils.setField(client,"base","http://ai.local");ReflectionTestUtils.setField(client,"token","test");ReflectionTestUtils.setField(client,"enabled",true);
        ReflectionTestUtils.setField(client,"datasetVersion","TEST_MOCK");
    }
    ObjectNode response(){return mapper.createObjectNode().put("transportVersion","EXPLORATION_JOB_TRANSPORT_V1_0").put("generationAuthorized",false).put("status","QUEUED").put("jobId",1).put("mutationKey","m").put("sourceContextHash",request.path("sourceContextHash").asText()).put("inputHash",request.path("inputHash").asText());}
    void reply(String op,String body){server.expect(requestTo("http://ai.local/v1/exploration/jobs/"+op)).andExpect(header("Authorization","Bearer test")).andExpect(method(HttpMethod.POST)).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));}
    @Test void validatesAuthenticatedJobResponse(){reply("enqueue",response().toString());assertThat(client.command("enqueue","owner","m",request).path("status").asText()).isEqualTo("QUEUED");server.verify();}
    @Test void mockCompletionIsExplicitlyNotGeneration(){var body=response().put("status","MOCK_COMPLETED");reply("read",body.toString());var result=client.command("read","owner","m",request);assertThat(result.path("status").asText()).isEqualTo("MOCK_COMPLETED");assertThat(result.path("generationAuthorized").asBoolean()).isFalse();assertThat(result.has("result")).isFalse();}
    @ParameterizedTest @ValueSource(strings={"inputHash","sourceContextHash","mutationKey","transportVersion","status","generationAuthorized","jobId","extra"})
    void rejectsMismatchedRemoteEnvelope(String field){var body=response();if(field.equals("generationAuthorized"))body.put(field,true);else if(field.equals("jobId"))body.put(field,-1);else body.put(field,"PRIVATE");reply("enqueue",body.toString());assertThatThrownBy(()->client.command("enqueue","owner","m",request)).isInstanceOf(ExplorationJobClient.Unavailable.class).hasMessageNotContaining("PRIVATE");}
    @Test void purgeWorksWhileJobsDisabled(){ReflectionTestUtils.setField(client,"enabled",false);reply("purge","{\"transportVersion\":\"EXPLORATION_JOB_TRANSPORT_V1_0\",\"status\":\"ERASED\",\"generationAuthorized\":false}");client.purge("owner");server.verify();}
    @Test void disabledJobCommandDoesNotSend(){ReflectionTestUtils.setField(client,"enabled",false);assertThatThrownBy(()->client.command("enqueue","owner","m",request)).isInstanceOf(ExplorationJobClient.Unavailable.class);server.verify();}
    @Test void remoteErrorsAreRedacted(){server.expect(requestTo("http://ai.local/v1/exploration/jobs/read")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("PRIVATE"));assertThatThrownBy(()->client.command("read","owner","m",request)).hasMessage("Exploration job command unavailable");}
    ObjectNode mockEnvelope(){
        ObjectNode bound=request.deepCopy();var ev=(ObjectNode)bound.path("evidence");ev.put("bindingStatus","BOUND").put("datasetVersion","TEST_MOCK");var refs=ev.putArray("referenceKeys");for(int i=0;i<bound.path("input").path("candidateNames").size();i++)refs.add("ref_"+String.format("%064x",i));
        var c=new ExplorationAiContract(new ExplorationJson(mapper));bound.put("inputHash",c.inputHash(bound));
        var output=mapper.createObjectNode();for(String key:java.util.List.of("contractVersion","requestId","inputHash","purpose"))output.set(key,bound.path(key));output.put("status","SUCCEEDED").put("changesRecommendation",false).putNull("reasonCode");var result=output.putObject("result").put("summary","[MOCK] contract");result.putArray("questions");result.set("referenceKeys",refs);
        var envelope=mapper.createObjectNode().put("transportVersion","EXPLORATION_MOCK_EXECUTION_V1_0").put("mode","MOCK").put("generationAuthorized",false).put("mutationKey","m").put("originalInputHash",request.path("inputHash").asText()).put("sourceContextHash",request.path("sourceContextHash").asText()).put("bindingHash","a".repeat(64)).put("jobId",1);envelope.set("boundRequest",bound);envelope.set("response",output);return envelope;
    }
    @Test void acceptsExplicitMockArtifact(){reply("mock-execute",mockEnvelope().toString());assertThat(client.executeMock("owner","m",request).path("mode").asText()).isEqualTo("MOCK");server.verify();}
    @ParameterizedTest @ValueSource(strings={"mode","originalInputHash","sourceContextHash","mutationKey","bindingHash","extra"})
    void rejectsUnboundOrMismatchedMockArtifact(String field){var body=mockEnvelope().put(field,"BAD");reply("mock-execute",body.toString());assertThatThrownBy(()->client.executeMock("owner","m",request)).isInstanceOf(ExplorationJobClient.Unavailable.class);}
    @ParameterizedTest @ValueSource(ints={400,401,403,409,422,429,500,503})
    void retryClassificationIsStatusBoundedAndRedacted(int status){
        server.expect(requestTo("http://ai.local/v1/exploration/jobs/read")).andRespond(withStatus(HttpStatusCode.valueOf(status)).body("PRIVATE_ERROR"));
        var error=catchThrowableOfType(()->client.command("read","owner","m",request),ExplorationJobClient.Unavailable.class);
        assertThat(error.retryable()).isEqualTo(status==429||status>=500);assertThat(error.getMessage()).doesNotContain("PRIVATE_ERROR");
    }
    @ParameterizedTest @ValueSource(strings={"EVIDENCE_INTEGRITY_ERROR","EVIDENCE_POLICY_INVALID","EVIDENCE_BINDING_DISABLED","EVIDENCE_STORE_UNAVAILABLE"})
    void known503CodesDistinguishPermanentFromTransient(String code){
        server.expect(requestTo("http://ai.local/v1/exploration/jobs/read")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON).body("{\"code\":\""+code+"\",\"message\":\"PRIVATE\"}"));
        var error=catchThrowableOfType(()->client.command("read","owner","m",request),ExplorationJobClient.Unavailable.class);
        assertThat(error.retryable()).isEqualTo(code.equals("EVIDENCE_STORE_UNAVAILABLE"));assertThat(error.getMessage()).doesNotContain("PRIVATE");
    }
    @Test void activeRemoteLeaseIsTransientInsteadOfGenericConflict(){
        server.expect(requestTo("http://ai.local/v1/exploration/jobs/read")).andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"EXPLORATION_JOB_BUSY\"}"));
        var error=catchThrowableOfType(()->client.command("read","owner","m",request),ExplorationJobClient.Unavailable.class);assertThat(error.retryable()).isTrue();
    }
    void modelReady(){ReflectionTestUtils.setField(client,"modelEnabled",true);ReflectionTestUtils.setField(client,"modelName","TEST_MODEL");}
    ObjectNode modelEnvelope(){var body=mockEnvelope().put("transportVersion","EXPLORATION_MODEL_EXECUTION_V1_0").put("mode","MODEL").put("generationAuthorized",true);body.putObject("providerMetadata").put("provider","openai").put("model","TEST_MODEL").put("promptVersion","EXPLORATION_MODEL_V1_0");return body;}
    @Test void modelConsumerRequiresItsOwnFlag(){assertThatThrownBy(()->client.executeModel("owner","m",request)).isInstanceOf(ExplorationJobClient.Unavailable.class);server.verify();}
    @Test void acceptsOnlyConfiguredModelAndProvenance(){modelReady();reply("model-execute",modelEnvelope().toString());assertThat(client.executeModel("owner","m",request).path("mode").asText()).isEqualTo("MODEL");server.verify();}
    @Test void conversationRequestAndPromptVersionSurviveAuthenticatedTransport(){
        var r=(ObjectNode)request.deepCopy();r.put("contractVersion",ExplorationAiContract.CONVERSATION_VERSION);
        var conversation=((ObjectNode)r.path("input")).putObject("conversation").put("question","SYNTHETIC question");conversation.putArray("history");
        r.put("inputHash",new ExplorationAiContract(new ExplorationJson(mapper)).inputHash(r));request=r;modelReady();
        var body=modelEnvelope();((ObjectNode)body.path("providerMetadata")).put("promptVersion","EXPLORATION_CONVERSATION_V1_0");reply("model-execute",body.toString());
        var result=client.executeModel("owner","m",request);assertThat(result.path("boundRequest").path("input").path("conversation")).isEqualTo(conversation);server.verify();
    }
    @ParameterizedTest @ValueSource(strings={"mode","permission","provider","model","prompt","hash","extra"})
    void rejectsModelEnvelopeMismatch(String field){modelReady();var body=modelEnvelope();switch(field){case "mode"->body.put("mode","MOCK");case "permission"->body.put("generationAuthorized",false);case "provider"->((ObjectNode)body.path("providerMetadata")).put("provider","other");case "model"->((ObjectNode)body.path("providerMetadata")).put("model","OTHER");case "prompt"->((ObjectNode)body.path("providerMetadata")).put("promptVersion","OTHER");case "hash"->body.put("originalInputHash","0".repeat(64));case "extra"->body.put("extra","PRIVATE");}reply("model-execute",body.toString());assertThatThrownBy(()->client.executeModel("owner","m",request)).isInstanceOf(ExplorationJobClient.Unavailable.class);}
}
