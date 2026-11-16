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
    private JsonNode post(String operation,JsonNode payload) {
        if(token==null||token.isBlank())throw new Unavailable();
        var headers=new HttpHeaders();headers.setContentType(MediaType.APPLICATION_JSON);headers.setBearerAuth(token);
        try {
            var raw=rest.exchange(base.replaceAll("/+$","")+"/v1/exploration/jobs/"+operation,HttpMethod.POST,
                    new HttpEntity<>(payload.toString(),headers),String.class);
            if(raw.getStatusCode().value()!=200 || raw.getBody()==null || raw.getBody().length()>4096)throw new Unavailable();
            var response=mapper.readTree(raw.getBody());
            if(!response.isObject() || !response.path("transportVersion").asText().equals(VERSION)
                    || !response.path("generationAuthorized").isBoolean() || response.path("generationAuthorized").asBoolean())throw new Unavailable();
            return response;
        } catch(org.springframework.web.client.HttpStatusCodeException e) {
            if(e.getStatusCode().value()==409)throw new Conflict();
            throw new Unavailable();
        } catch(Exception e) {throw new Unavailable();}
    }
}
