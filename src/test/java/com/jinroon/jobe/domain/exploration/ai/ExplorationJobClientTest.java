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
    }
    ObjectNode response(){return mapper.createObjectNode().put("transportVersion","EXPLORATION_JOB_TRANSPORT_V1_0").put("generationAuthorized",false).put("status","QUEUED").put("jobId",1).put("mutationKey","m").put("sourceContextHash",request.path("sourceContextHash").asText()).put("inputHash",request.path("inputHash").asText());}
    void reply(String op,String body){server.expect(requestTo("http://ai.local/v1/exploration/jobs/"+op)).andExpect(header("Authorization","Bearer test")).andExpect(method(HttpMethod.POST)).andRespond(withSuccess(body,MediaType.APPLICATION_JSON));}
    @Test void validatesAuthenticatedJobResponse(){reply("enqueue",response().toString());assertThat(client.command("enqueue","owner","m",request).path("status").asText()).isEqualTo("QUEUED");server.verify();}
    @ParameterizedTest @ValueSource(strings={"inputHash","sourceContextHash","mutationKey","transportVersion","status","generationAuthorized","jobId","extra"})
    void rejectsMismatchedRemoteEnvelope(String field){var body=response();if(field.equals("generationAuthorized"))body.put(field,true);else if(field.equals("jobId"))body.put(field,-1);else body.put(field,"PRIVATE");reply("enqueue",body.toString());assertThatThrownBy(()->client.command("enqueue","owner","m",request)).isInstanceOf(ExplorationJobClient.Unavailable.class).hasMessageNotContaining("PRIVATE");}
    @Test void purgeWorksWhileJobsDisabled(){ReflectionTestUtils.setField(client,"enabled",false);reply("purge","{\"transportVersion\":\"EXPLORATION_JOB_TRANSPORT_V1_0\",\"status\":\"ERASED\",\"generationAuthorized\":false}");client.purge("owner");server.verify();}
    @Test void disabledJobCommandDoesNotSend(){ReflectionTestUtils.setField(client,"enabled",false);assertThatThrownBy(()->client.command("enqueue","owner","m",request)).isInstanceOf(ExplorationJobClient.Unavailable.class);server.verify();}
    @Test void remoteErrorsAreRedacted(){server.expect(requestTo("http://ai.local/v1/exploration/jobs/read")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).body("PRIVATE"));assertThatThrownBy(()->client.command("read","owner","m",request)).hasMessage("Exploration job command unavailable");}
}
