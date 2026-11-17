package com.jinroon.jobe.domain.exploration.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/** Trusted internal job commands. Never forwards raw remote error text. */
@Component
@RequiredArgsConstructor
public class ExplorationJobClient {
    private final RestTemplate rest;
    private final ObjectMapper mapper;
    private final ExplorationAiContract contract;
    @Value("${ai.server.url}") private String base;
    @Value("${ai.server.token}") private String token;
    @Value("${exploration.jobs.enabled:false}") private boolean enabled;
    @Value("${exploration.evidence.dataset-version:}") private String datasetVersion;
    public static class Unavailable extends RuntimeException {public Unavailable(){super("Exploration job command unavailable");}}
    public static class Conflict extends Unavailable {}
    private static final String VERSION="EXPLORATION_JOB_TRANSPORT_V1_0";
    public JsonNode command(String operation,String owner,String mutation,JsonNode request) {
        if(!enabled || !Set.of("enqueue","read","cancel").contains(operation))throw new Unavailable();
        contract.request(request);
        if(!request.path("evidence").path("bindingStatus").asText().equals("NOT_BOUND"))throw new Unavailable();
        var payload=mapper.createObjectNode().put("ownerKey",owner).put("mutationKey",mutation);
        payload.set("request",request.deepCopy());
        JsonNode response=post(operation,payload);
        if(response.size()!=7 || !response.path("mutationKey").asText().equals(mutation)
                || !response.path("sourceContextHash").equals(request.path("sourceContextHash"))
                || !response.path("inputHash").equals(request.path("inputHash")))throw new Unavailable();
        String state=response.path("status").asText();
        if(!Set.of("QUEUED","LEASED","BLOCKED","CANCELLED","MOCK_COMPLETED","INSUFFICIENT_EVIDENCE").contains(state))throw new Unavailable();
        if(state.equals("INSUFFICIENT_EVIDENCE")) {
            if(!response.path("jobId").isNull() || !operation.equals("enqueue"))throw new Unavailable();
        } else if(!response.path("jobId").isIntegralNumber() || !response.path("jobId").canConvertToLong() || response.path("jobId").asLong()<=0)throw new Unavailable();
        if(operation.equals("cancel") && !state.equals("CANCELLED"))throw new Unavailable();
        return response.deepCopy();
    }
    public void purge(String owner) {
        var response=post("purge",mapper.createObjectNode().put("ownerKey",owner));
        if(response.size()!=3 || !response.path("status").asText().equals("ERASED"))throw new Unavailable();
    }
    public JsonNode executeMock(String owner,String mutation,JsonNode request){
        if(!enabled || datasetVersion==null || !datasetVersion.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}"))throw new Unavailable();contract.request(request);
        if(!request.path("evidence").path("bindingStatus").asText().equals("NOT_BOUND"))throw new Unavailable();
        var payload=mapper.createObjectNode().put("ownerKey",owner).put("mutationKey",mutation);payload.set("request",request.deepCopy());
        var result=post("mock-execute",payload,"EXPLORATION_MOCK_EXECUTION_V1_0");
        if(result.size()!=10 || !result.path("mode").asText().equals("MOCK") || !result.path("mutationKey").asText().equals(mutation)
                || !result.path("originalInputHash").equals(request.path("inputHash")) || !result.path("sourceContextHash").equals(request.path("sourceContextHash"))
                || !result.path("bindingHash").asText().matches("[a-f0-9]{64}") || !result.path("jobId").isIntegralNumber() || result.path("jobId").asLong()<=0)throw new Unavailable();
        try{contract.request(result.path("boundRequest"));contract.response(result.path("boundRequest"),result.path("response"));}
        catch(ExplorationAiContract.Invalid e){throw new Unavailable();}
        if(!datasetVersion.equals(result.path("boundRequest").path("evidence").path("datasetVersion").asText()))throw new Unavailable();
        for(String key:java.util.List.of("contractVersion","requestId","sourceContextHash","purpose","mode","testOnly","input"))if(!request.path(key).equals(result.path("boundRequest").path(key)))throw new Unavailable();
        return result;
    }
    private JsonNode post(String operation,JsonNode payload) {
        return post(operation,payload,VERSION);
    }
    private JsonNode post(String operation,JsonNode payload,String expectedVersion) {
        if(token==null||token.isBlank())throw new Unavailable();
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.setBearerAuth(token);
        try {
            var raw=rest.exchange(base.replaceAll("/+$","")+"/v1/exploration/jobs/"+operation,HttpMethod.POST,
                    new HttpEntity<>(payload.toString(),headers),String.class);
            if(raw.getStatusCode().value()!=200 || raw.getBody()==null || raw.getBody().length()>(operation.equals("mock-execute")?65536:4096))throw new Unavailable();
            var response=mapper.readTree(raw.getBody());
            if(!response.isObject() || !response.path("transportVersion").asText().equals(expectedVersion)
                    || !response.path("generationAuthorized").isBoolean() || response.path("generationAuthorized").asBoolean())throw new Unavailable();
            return response;
        } catch(org.springframework.web.client.HttpStatusCodeException e) {
            if(e.getStatusCode().value()==409)throw new Conflict();
            throw new Unavailable();
        } catch(Exception e) {throw new Unavailable();}
    }
}
