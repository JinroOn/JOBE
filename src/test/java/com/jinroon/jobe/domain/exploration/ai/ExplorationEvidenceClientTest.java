package com.jinroon.jobe.domain.exploration.ai;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

class ExplorationEvidenceClientTest {
    final ObjectMapper mapper=new ObjectMapper();
    final ExplorationAiContract contract=new ExplorationAiContract(new ExplorationJson(mapper));
    final RestTemplate rest=new RestTemplate();
    final ExplorationEvidenceClient client=new ExplorationEvidenceClient(rest,contract,mapper);
    final MockRestServiceServer server=MockRestServiceServer.bindTo(rest).build();
    JsonNode original;
    @BeforeEach void setup()throws Exception{
        original=mapper.readTree(Files.readString(Path.of("datasets/exploration-ai-contract/cases.json"))).path("adapterCases").get(0).path("expectedRequest");
        ReflectionTestUtils.setField(client,"baseUrl","http://ai.local/");
        ReflectionTestUtils.setField(client,"token","test-token");
        ReflectionTestUtils.setField(client,"enabled",true);
        ReflectionTestUtils.setField(client,"datasetVersion","TEST_BINDING_V1");
    }
    ObjectNode response(boolean bound){
        ObjectNode request=original.deepCopy();
        if(bound){var ev=(ObjectNode)request.path("evidence");ev.put("bindingStatus","BOUND");ev.put("datasetVersion","TEST_BINDING_V1");
            var refs=ev.putArray("referenceKeys");int n=original.path("input").path("targetName").isNull()?original.path("input").path("candidateNames").size():1;
            for(int i=0;i<n;i++)refs.add("ref_"+String.format("%064x",i));
            request.put("inputHash",contract.inputHash(request));}
        var out=mapper.createObjectNode().put("transportVersion","EXPLORATION_EVIDENCE_TRANSPORT_V1_0").put("status",bound?"BOUND":"INSUFFICIENT_EVIDENCE").put("generationAuthorized",false);
        if(bound)out.putNull("reasonCode");else out.put("reasonCode","APPROVED_EVIDENCE_UNAVAILABLE");out.set("request",request);return out;
    }
    void respond(JsonNode body){server.expect(requestTo("http://ai.local/v1/exploration/evidence/prepare"))
        .andExpect(method(HttpMethod.POST)).andExpect(header("Authorization","Bearer test-token"))
        .andExpect(header("X-Request-Id",original.path("requestId").asText())).andExpect(content().json(original.toString()))
        .andRespond(withSuccess(body.toString(),MediaType.APPLICATION_JSON));}
    @Test void sendsMinimalAuthenticatedRequestAndValidatesBoundEnvelope(){respond(response(true));var out=client.prepare(original);assertThat(out.status()).isEqualTo("BOUND");assertThat(out.readyForGeneration()).isFalse();assertThat(out.request().path("evidence").path("bindingStatus").asText()).isEqualTo("BOUND");server.verify();}
    @Test void missingEvidenceIsNotAnOutage(){respond(response(false));var out=client.prepare(original);assertThat(out.status()).isEqualTo("INSUFFICIENT_EVIDENCE");assertThat(out.retryable()).isFalse();server.verify();}
    @Test void disabledOrUnconfiguredNeverCallsTransport(){ReflectionTestUtils.setField(client,"enabled",false);assertThat(client.prepare(original).reasonCode()).isEqualTo("EVIDENCE_BINDING_DISABLED");ReflectionTestUtils.setField(client,"enabled",true);ReflectionTestUtils.setField(client,"token","");assertThat(client.prepare(original).reasonCode()).isEqualTo("EVIDENCE_POLICY_INVALID");server.verify();}
    @ParameterizedTest @ValueSource(strings={"requestId","sourceContextHash","inputHash","datasetVersion","generation","extra","missingReason","referenceCount"})
    void rejectsMismatchedOrForgedResponse(String mutation){
        var body=response(true);var r=(ObjectNode)body.path("request");
        switch(mutation){case "requestId"->r.put("requestId","another");case "sourceContextHash"->r.put("sourceContextHash","f".repeat(64));case "inputHash"->r.put("inputHash","0".repeat(64));
            case "datasetVersion"->{((ObjectNode)r.path("evidence")).put("datasetVersion","OTHER");r.put("inputHash",contract.inputHash(r));}
            case "generation"->body.put("generationAuthorized",true);case "extra"->body.put("private","DO_NOT_DISCLOSE");case "missingReason"->body.remove("reasonCode");
            case "referenceCount"->{((ObjectNode)r.path("evidence")).putArray("referenceKeys").add("ref_"+"a".repeat(64)).add("ref_"+"b".repeat(64)).add("ref_"+"c".repeat(64)).add("ref_"+"d".repeat(64)).add("ref_"+"e".repeat(64)).add("ref_"+"f".repeat(64)).add("ref_"+"0".repeat(64)).add("ref_"+"1".repeat(64)).add("ref_"+"2".repeat(64));r.put("inputHash",contract.inputHash(r));}}
        respond(body);var out=client.prepare(original);assertThat(out.status()).isEqualTo("INVALID_RESPONSE");assertThat(out.request()).isNull();assertThat(out.toString()).doesNotContain("DO_NOT_DISCLOSE");server.verify();
    }
    @ParameterizedTest @ValueSource(ints={401,403,409,422,429,500,503})
    void httpFailuresNeverBecomeMissingEvidenceOrExposeBody(int status){server.expect(requestTo("http://ai.local/v1/exploration/evidence/prepare")).andRespond(withStatus(HttpStatusCode.valueOf(status)).body("PRIVATE_ERROR"));var out=client.prepare(original);assertThat(out.status()).isNotEqualTo("INSUFFICIENT_EVIDENCE");assertThat(out.request()).isNull();assertThat(out.toString()).doesNotContain("PRIVATE_ERROR");server.verify();}
    @ParameterizedTest @ValueSource(strings={"EVIDENCE_INTEGRITY_ERROR","EVIDENCE_STORE_UNAVAILABLE","EVIDENCE_POLICY_INVALID","EVIDENCE_BINDING_DISABLED"})
    void preservesOnlyAllowlistedRemote503Codes(String code){server.expect(requestTo("http://ai.local/v1/exploration/evidence/prepare")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE).contentType(MediaType.APPLICATION_JSON).body("{\"code\":\""+code+"\",\"message\":\"PRIVATE\"}"));var out=client.prepare(original);assertThat(out.reasonCode()).isEqualTo(code);assertThat(out.retryable()).isEqualTo(code.equals("EVIDENCE_STORE_UNAVAILABLE"));assertThat(out.toString()).doesNotContain("PRIVATE");server.verify();}
    @Test void timeoutIsRetryableAndDoesNotLeakDetails(){server.expect(requestTo("http://ai.local/v1/exploration/evidence/prepare")).andRespond(r->{throw new java.net.SocketTimeoutException("PRIVATE");});var out=client.prepare(original);assertThat(out.reasonCode()).isEqualTo("EVIDENCE_TRANSPORT_UNAVAILABLE");assertThat(out.retryable()).isTrue();assertThat(out.toString()).doesNotContain("PRIVATE");}
}