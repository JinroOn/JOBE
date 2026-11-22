package com.jinroon.jobe.domain.exploration.api;

import com.jinroon.jobe.domain.exploration.context.MajorExplanationService;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import com.jinroon.jobe.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/exploration/v1/results/{resultId}/majors/{majorCode}/explanation")
public class MajorExplanationController {
    private final MajorExplanationService explanations;
    private final ExplorationJson json;

    @GetMapping
    public Object get(@AuthenticationPrincipal CustomUserDetails user, @PathVariable long resultId,
                      @PathVariable String majorCode) {
        return json.responseValue(explanations.get(user.getUserId(), resultId, majorCode));
    }

    @PostMapping
    public ResponseEntity<Object> request(@AuthenticationPrincipal CustomUserDetails user, @PathVariable long resultId,
                                          @PathVariable String majorCode) {
        var explanation = explanations.request(user.getUserId(), resultId, majorCode);
        return mutationResponse(explanation);
    }

    @PostMapping("/retry")
    public ResponseEntity<Object> retry(@AuthenticationPrincipal CustomUserDetails user, @PathVariable long resultId,
                                        @PathVariable String majorCode) {
        return mutationResponse(explanations.retry(user.getUserId(), resultId, majorCode));
    }

    private ResponseEntity<Object> mutationResponse(com.fasterxml.jackson.databind.JsonNode explanation) {
        int status = "GENERATING".equals(explanation.path("status").asText()) ? 202 : 200;
        return ResponseEntity.status(status).body(json.responseValue(explanation));
    }
}
