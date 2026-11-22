package com.jinroon.jobe.domain.exploration.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

/** Internal preparation transport. No raw body logging or legacy fallback. */
@Component
@RequiredArgsConstructor
public class ExplorationEvidenceClient {
    private final RestTemplate restTemplate;
    private final ExplorationAiContract contract;
    private final ObjectMapper mapper;
    @Value("${ai.server.url}") private String baseUrl;
    @Value("${ai.server.token}") private String token;
    @Value("${exploration.evidence.enabled:false}") private boolean enabled;
    @Value("${exploration.evidence.dataset-version:}") private String datasetVersion;
    public record Outcome(String status, String reasonCode, boolean retryable, JsonNode request) {
        public boolean readyForGeneration() { return false; }
    }

    public Outcome prepare(JsonNode original) {
        contract.request(original);
        if (!"NOT_BOUND".equals(original.path("evidence").path("bindingStatus").asText()))
            return failure("INVALID_INPUT", "EVIDENCE_CONTRACT_INVALID", false);
        if (!enabled) return failure("UNAVAILABLE", "EVIDENCE_BINDING_DISABLED", false);
        if (token == null || token.isBlank() || datasetVersion == null || !datasetVersion.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}"))
            return failure("UNAVAILABLE", "EVIDENCE_POLICY_INVALID", false);
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        headers.set("X-Request-Id", original.path("requestId").asText());
        try {
            var response = restTemplate.exchange(baseUrl.replaceAll("/+$", "") + "/v1/exploration/evidence/prepare",
                    HttpMethod.POST, new HttpEntity<>(original.toString(), headers), String.class);
            if (response.getStatusCode().value() != 200) return failure("INVALID_RESPONSE", "EVIDENCE_RESPONSE_INVALID", false);
            try { return validate(original, response.getBody()==null?null:mapper.readTree(response.getBody())); }
            catch (com.fasterxml.jackson.core.JsonProcessingException e) { return failure("INVALID_RESPONSE","EVIDENCE_RESPONSE_INVALID",false); }
        } catch (HttpStatusCodeException e) {
            return switch (e.getStatusCode().value()) {
                case 401, 403 -> failure("UNAVAILABLE", "EVIDENCE_AUTH_FAILED", false);
                case 409 -> failure("UNAVAILABLE", "EVIDENCE_NO_LONGER_AVAILABLE", false);
                case 422 -> failure("INVALID_INPUT", "EVIDENCE_CONTRACT_INVALID", false);
                case 503 -> remoteUnavailable(e);
                case 429 -> failure("UNAVAILABLE", "EVIDENCE_RATE_LIMITED", true);
                default -> failure("UNAVAILABLE", "EVIDENCE_SERVICE_UNAVAILABLE", e.getStatusCode().is5xxServerError());
            };
        } catch (RestClientException e) {
            return failure("UNAVAILABLE", "EVIDENCE_TRANSPORT_UNAVAILABLE", true);
        }
    }

    private Outcome remoteUnavailable(HttpStatusCodeException error) {
        try {
            String body=error.getResponseBodyAsString();
            if(body.length()>4096) return failure("UNAVAILABLE","EVIDENCE_SERVICE_UNAVAILABLE",true);
            String code=mapper.readTree(body).path("code").asText();
            if(Set.of("EVIDENCE_BINDING_DISABLED","EVIDENCE_POLICY_INVALID","EVIDENCE_INTEGRITY_ERROR","EVIDENCE_STORE_UNAVAILABLE").contains(code))
                return failure("UNAVAILABLE",code,code.equals("EVIDENCE_STORE_UNAVAILABLE"));
        } catch (Exception ignored) { /* Untrusted error body; never expose its contents. */ }
        return failure("UNAVAILABLE","EVIDENCE_SERVICE_UNAVAILABLE",true);
    }

    private Outcome validate(JsonNode original, JsonNode response) {
        try {
            if (response == null || !response.isObject() || response.size() != 5
                    || !response.has("transportVersion") || !response.has("status") || !response.has("reasonCode")
                    || !response.has("generationAuthorized") || !response.has("request")
                    || !"EXPLORATION_EVIDENCE_TRANSPORT_V1_0".equals(response.path("transportVersion").asText())
                    || !response.path("generationAuthorized").isBoolean() || response.path("generationAuthorized").asBoolean())
                throw new ExplorationAiContract.Invalid();
            JsonNode bound = response.path("request");
            contract.request(bound);
            for (String field : Set.of("contractVersion", "requestId", "sourceContextHash", "purpose", "mode", "testOnly", "input"))
                if (!original.path(field).equals(bound.path(field))) throw new ExplorationAiContract.Invalid();
            String status = response.path("status").asText();
            if (status.equals("INSUFFICIENT_EVIDENCE") && original.equals(bound)
                    && "APPROVED_EVIDENCE_UNAVAILABLE".equals(response.path("reasonCode").asText()))
                return new Outcome(status, "APPROVED_EVIDENCE_UNAVAILABLE", false, bound.deepCopy());
            if (!status.equals("BOUND") || !response.path("reasonCode").isNull()
                    || !"BOUND".equals(bound.path("evidence").path("bindingStatus").asText())
                    || !datasetVersion.equals(bound.path("evidence").path("datasetVersion").asText()))
                throw new ExplorationAiContract.Invalid();
            int subjects = original.path("input").path("targetName").isNull()
                    ? original.path("input").path("candidateNames").size() : 1;
            int references = bound.path("evidence").path("referenceKeys").size();
            boolean explanation = "EXPLANATION".equals(original.path("purpose").asText());
            if (subjects == 0 || (explanation ? references < 1 || references > 2 : references != subjects))
                throw new ExplorationAiContract.Invalid();
            for (JsonNode ref : bound.path("evidence").path("referenceKeys"))
                if (!ref.asText().matches("ref_[a-f0-9]{64}")) throw new ExplorationAiContract.Invalid();
            return new Outcome("BOUND", "GENERATION_NOT_IMPLEMENTED", false, bound.deepCopy());
        } catch (ExplorationAiContract.Invalid e) {
            return failure("INVALID_RESPONSE", "EVIDENCE_RESPONSE_INVALID", false);
        }
    }
    private Outcome failure(String status, String reason, boolean retryable) { return new Outcome(status, reason, retryable, null); }
}