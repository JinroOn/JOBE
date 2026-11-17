package com.jinroon.jobe.domain.exploration.api;

import com.jinroon.jobe.domain.exploration.service.ExplorationSessionService;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import com.jinroon.jobe.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/exploration/v1/sessions")
@RequiredArgsConstructor
public class ExplorationSessionController {
    private final ExplorationSessionService service;
    private final ExplorationJson json;

    @PostMapping
    public ResponseEntity<Object> start(@AuthenticationPrincipal CustomUserDetails user,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody ExplorationRequests.Start request) {
        return reply(service.start(user.getUserId(), key, request.getDiagnosisType()));
    }

    @GetMapping
    public Object list(@AuthenticationPrincipal CustomUserDetails user,
            @RequestParam(defaultValue = "EXPLORATION_V1") String type) {
        return json.responseValue(service.list(user.getUserId(), type));
    }

    @GetMapping("/{sessionId}")
    public Object get(@AuthenticationPrincipal CustomUserDetails user, @PathVariable long sessionId) {
        return json.responseValue(service.get(user.getUserId(), sessionId));
    }

    @PostMapping("/{sessionId}/next")
    public ResponseEntity<Object> next(@AuthenticationPrincipal CustomUserDetails user, @PathVariable long sessionId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody ExplorationRequests.Mutation request) {
        return reply(service.next(user.getUserId(), sessionId, key, request.getExpectedRevision()));
    }

    @PostMapping("/{sessionId}/abandon")
    public ResponseEntity<Object> abandon(@AuthenticationPrincipal CustomUserDetails user, @PathVariable long sessionId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody ExplorationRequests.Mutation request) {
        return reply(service.abandon(user.getUserId(), sessionId, key, request.getExpectedRevision()));
    }

    @PostMapping("/{sessionId}/advance")
    public ResponseEntity<Object> advance(@AuthenticationPrincipal CustomUserDetails user, @PathVariable long sessionId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody ExplorationRequests.Mutation request) {
        return reply(service.advance(user.getUserId(), sessionId, key, request.getExpectedRevision()));
    }

    @PostMapping("/{sessionId}/complete")
    public ResponseEntity<Object> complete(@AuthenticationPrincipal CustomUserDetails user, @PathVariable long sessionId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody ExplorationRequests.Mutation request) {
        return reply(service.complete(user.getUserId(), sessionId, key, request.getExpectedRevision()));
    }

    @GetMapping("/{sessionId}/result")
    public Object result(@AuthenticationPrincipal CustomUserDetails user, @PathVariable long sessionId) {
        return json.responseValue(service.resultBySession(user.getUserId(), sessionId));
    }

    @PutMapping("/{sessionId}/answers/{issuedId}")
    public ResponseEntity<Object> answer(@AuthenticationPrincipal CustomUserDetails user, @PathVariable long sessionId,
            @PathVariable long issuedId, @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody ExplorationRequests.Answer request) {
        return reply(service.answer(user.getUserId(), sessionId, issuedId, key, request.getExpectedRevision(),
                request.getOptionCode(), request.getClientMutationId()));
    }

    @PutMapping("/{sessionId}/presentation")
    public ResponseEntity<Object> presentation(@AuthenticationPrincipal CustomUserDetails user, @PathVariable long sessionId,
            @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody ExplorationRequests.Presentation request,
            jakarta.servlet.http.HttpServletRequest http) {
        if (http.getQueryString() != null) throw new ExplorationApiException(400, "INVALID_REQUEST", "쿼리 파라미터를 지원하지 않습니다.");
        return reply(service.presentation(user.getUserId(), sessionId, key, request.getExpectedRevision(),
                request.getCheckpointId(), request.getUnexpected(), request.getUnfamiliar()));
    }

    private ResponseEntity<Object> reply(ExplorationSessionService.Reply result) {
        return ResponseEntity.status(result.status()).body(json.responseValue(result.body()));
    }
}
