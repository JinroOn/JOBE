package com.jinroon.jobe.domain.exploration.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** Trusted internal job commands. Never forwards raw remote error text. */
@Component
public class ExplorationJobClient {
    private final RestTemplate rest;
    private final RestTemplate modelRest;
    private final ObjectMapper mapper;
    private final ExplorationAiContract contract;
    @org.springframework.beans.factory.annotation.Autowired
    public ExplorationJobClient(RestTemplate rest,ObjectMapper mapper,ExplorationAiContract contract,
            @org.springframework.beans.factory.annotation.Qualifier("explorationModelRestTemplate") RestTemplate modelRest) {
        this.rest=rest;this.mapper=mapper;this.contract=contract;this.modelRest=modelRest;
    }
    public ExplorationJobClient(RestTemplate rest,ObjectMapper mapper,ExplorationAiContract contract) {
        this(rest,mapper,contract,rest);
    }
    @Value("${ai.server.url}") private String base;
    @Value("${ai.server.token}") private String token;
    @Value("${exploration.jobs.enabled:false}") private boolean enabled;
    @Value("${exploration.evidence.dataset-version:}") private String datasetVersion;
    @Value("${exploration.jobs.model-enabled:false}") private boolean modelEnabled;
    @Value("${exploration.jobs.model-name:}") private String modelName;
    public enum Failure { TRANSIENT, CONFIGURATION, AUTHENTICATION, INVALID_RESPONSE, INVALID_REQUEST, CONFLICT, EVIDENCE_UNAVAILABLE, INTEGRITY }
    public static class Unavailable extends RuntimeException {
        private final Failure failure;
        public Unavailable(){this(Failure.TRANSIENT);}
        public Unavailable(Failure failure){super("Exploration job command unavailable");this.failure=failure;}
        public Failure failure(){return failure;}
        public boolean retryable(){return failure==Failure.TRANSIENT;}
    }
    public static class Conflict extends Unavailable {public Conflict(){super(Failure.CONFLICT);}}
    private static Unavailable invalid(){return new Unavailable(Failure.INVALID_RESPONSE);}
    public boolean readyForMock(){return enabled && token!=null && !token.isBlank() && base!=null && !base.isBlank() && datasetVersion!=null && datasetVersion.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}");}
    private static final String VERSION="EXPLORATION_JOB_TRANSPORT_V1_0";
    public JsonNode command(String operation,String owner,String mutation,JsonNode request) {
        if(!enabled)throw new Unavailable(Failure.CONFIGURATION);
        if(!Set.of("enqueue","read","cancel").contains(operation))throw new Unavailable(Failure.INVALID_REQUEST);
        contract.request(request);
        if(!request.path("evidence").path("bindingStatus").asText().equals("NOT_BOUND"))throw invalid();
        var payload=mapper.createObjectNode().put("ownerKey",owner).put("mutationKey",mutation);
        payload.set("request",request.deepCopy());
        JsonNode response=post(operation,payload);
        if(response.size()!=7 || !response.path("mutationKey").asText().equals(mutation)
                || !response.path("sourceContextHash").equals(request.path("sourceContextHash"))
                || !response.path("inputHash").equals(request.path("inputHash")))throw invalid();
        String state=response.path("status").asText();
        if(!Set.of("QUEUED","LEASED","BLOCKED","CANCELLED","MOCK_COMPLETED","MODEL_COMPLETED","INSUFFICIENT_EVIDENCE").contains(state))throw invalid();
        if(state.equals("INSUFFICIENT_EVIDENCE")) {
            if(!response.path("jobId").isNull() || !operation.equals("enqueue"))throw invalid();
        } else if(!response.path("jobId").isIntegralNumber() || !response.path("jobId").canConvertToLong() || response.path("jobId").asLong()<=0)throw invalid();
        if(operation.equals("cancel") && !state.equals("CANCELLED"))throw invalid();
        return response.deepCopy();
    }
    public void purge(String owner) {
        var response=post("purge",mapper.createObjectNode().put("ownerKey",owner));
        if(response.size()!=3 || !response.path("status").asText().equals("ERASED"))throw invalid();
    }
    public JsonNode executeMock(String owner,String mutation,JsonNode request){
        if(!readyForMock())throw new Unavailable(Failure.CONFIGURATION);contract.request(request);
        if(!request.path("evidence").path("bindingStatus").asText().equals("NOT_BOUND"))throw invalid();
        var payload=mapper.createObjectNode().put("ownerKey",owner).put("mutationKey",mutation);payload.set("request",request.deepCopy());
        var result=post("mock-execute",payload,"EXPLORATION_MOCK_EXECUTION_V1_0");
        if(result.size()!=10 || !result.path("mode").asText().equals("MOCK") || !result.path("mutationKey").asText().equals(mutation)
                || !result.path("originalInputHash").equals(request.path("inputHash")) || !result.path("sourceContextHash").equals(request.path("sourceContextHash"))
                || !result.path("bindingHash").asText().matches("[a-f0-9]{64}") || !result.path("jobId").isIntegralNumber() || result.path("jobId").asLong()<=0)throw invalid();
        try{contract.request(result.path("boundRequest"));contract.response(result.path("boundRequest"),result.path("response"));}
        catch(ExplorationAiContract.Invalid e){throw invalid();}
        if(!datasetVersion.equals(result.path("boundRequest").path("evidence").path("datasetVersion").asText()))throw invalid();
        for(String key:java.util.List.of("contractVersion","requestId","sourceContextHash","purpose","mode","testOnly","input"))if(!request.path(key).equals(result.path("boundRequest").path(key)))throw invalid();
        return result;
    }
    private JsonNode post(String operation,JsonNode payload) {
        return post(operation,payload,VERSION);
    }
    public JsonNode executeModel(String owner,String mutation,JsonNode request){
        if(!modelEnabled || !readyForMock() || modelName==null || modelName.isBlank())throw new Unavailable(Failure.CONFIGURATION);
        contract.request(request);if(!request.path("evidence").path("bindingStatus").asText().equals("NOT_BOUND"))throw invalid();
        var payload=mapper.createObjectNode().put("ownerKey",owner).put("mutationKey",mutation);payload.set("request",request.deepCopy());
        var result=post("model-execute",payload,"EXPLORATION_MODEL_EXECUTION_V1_0");
        if(result.size()!=11 || !result.path("mode").asText().equals("MODEL") || !result.path("mutationKey").asText().equals(mutation)
                || !result.path("originalInputHash").equals(request.path("inputHash")) || !result.path("sourceContextHash").equals(request.path("sourceContextHash"))
                || !result.path("bindingHash").asText().matches("[a-f0-9]{64}") || !result.path("jobId").isIntegralNumber() || !result.path("jobId").canConvertToLong() || result.path("jobId").asLong()<=0)throw invalid();
        String prompt=ExplorationAiContract.promptVersion(request);
        var metadata=result.path("providerMetadata");if(metadata.size()!=3 || !Set.of("openai","openai_compatible","factchat").contains(metadata.path("provider").asText()) || !modelName.equals(metadata.path("model").asText()) || !prompt.equals(metadata.path("promptVersion").asText()))throw invalid();
        try{contract.request(result.path("boundRequest"));contract.response(result.path("boundRequest"),result.path("response"));}catch(ExplorationAiContract.Invalid e){throw invalid();}
        if(!datasetVersion.equals(result.path("boundRequest").path("evidence").path("datasetVersion").asText()))throw invalid();
        for(String key:java.util.List.of("contractVersion","requestId","sourceContextHash","purpose","mode","testOnly","input"))if(!request.path(key).equals(result.path("boundRequest").path(key)))throw invalid();
        return result;
    }
    private JsonNode post(String operation,JsonNode payload,String expectedVersion) {
        if(token==null||token.isBlank())throw new Unavailable(Failure.CONFIGURATION);
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.setBearerAuth(token);
        try {
            var transport=operation.equals("model-execute")?modelRest:rest;
            var raw=transport.exchange(base.replaceAll("/+$","")+"/v1/exploration/jobs/"+operation,HttpMethod.POST,
                    new HttpEntity<>(payload.toString(),headers),String.class);
            if(raw.getStatusCode().value()!=200 || raw.getBody()==null || raw.getBody().length()>(operation.endsWith("execute")?65536:4096))throw invalid();
            var response=mapper.readTree(raw.getBody());
            if(!response.isObject() || !response.path("transportVersion").asText().equals(expectedVersion)
                    || !response.path("generationAuthorized").isBoolean() || response.path("generationAuthorized").asBoolean()!=expectedVersion.equals("EXPLORATION_MODEL_EXECUTION_V1_0"))throw invalid();
            return response;
        } catch(org.springframework.web.client.HttpStatusCodeException e) {
            int status=e.getStatusCode().value();
            if(status==409){
                try{String body=e.getResponseBodyAsString();if(body.length()<=4096 && mapper.readTree(body).path("code").asText().equals("EXPLORATION_JOB_BUSY"))throw new Unavailable(Failure.TRANSIENT);}
                catch(Unavailable known){throw known;}catch(Exception ignored){}
                throw new Conflict();
            }
            if(status==401 || status==403)throw new Unavailable(Failure.AUTHENTICATION);
            if(status==400 || status==422)throw new Unavailable(Failure.INVALID_REQUEST);
            if(status==503){
                try {
                    String body=e.getResponseBodyAsString();
                    if(body.length()<=4096){String code=mapper.readTree(body).path("code").asText();
                        if(code.equals("EVIDENCE_INTEGRITY_ERROR"))throw new Unavailable(Failure.INTEGRITY);
                        if(java.util.Set.of("EVIDENCE_POLICY_INVALID","EVIDENCE_BINDING_DISABLED").contains(code))throw new Unavailable(Failure.CONFIGURATION);
                        if(code.equals("MODEL_CONFIGURATION_INVALID"))throw new Unavailable(Failure.CONFIGURATION);
                        if(code.equals("MODEL_AUTH_FAILED"))throw new Unavailable(Failure.AUTHENTICATION);
                    }
                }catch(Unavailable known){throw known;}catch(Exception ignored){}
            }
            throw new Unavailable(status==429 || status>=500?Failure.TRANSIENT:Failure.INVALID_REQUEST);
        } catch(Unavailable e) {throw e;}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw invalid();}
        catch(org.springframework.web.client.RestClientException e){throw new Unavailable(Failure.TRANSIENT);}
        catch(RuntimeException e){throw invalid();}
    }
}
