package com.jinroon.jobe.domain.exploration.api;

import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import com.jinroon.jobe.domain.exploration.support.PostResultSupportService;
import com.jinroon.jobe.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/exploration/v1/results/{resultId}/support")
public class PostResultSupportController {
    private final PostResultSupportService service;
    private final ExplorationJson json;
    @GetMapping public Object get(@AuthenticationPrincipal CustomUserDetails user,@PathVariable long resultId){return json.responseValue(service.get(user.getUserId(),resultId));}
    @PostMapping public ResponseEntity<Object> select(@AuthenticationPrincipal CustomUserDetails user,@PathVariable long resultId,
            @RequestHeader("Idempotency-Key")String key,@Valid @RequestBody SupportRequests.Select body){
        var reply=service.select(user.getUserId(),resultId,key,body.getExpectedSupportRevision(),body.getPurpose(),body.getTargetMajorCode());
        return ResponseEntity.status(reply.status()).body(json.responseValue(reply.body()));
    }
    @PutMapping("/answers/{issuedId}") public ResponseEntity<Object> answer(@AuthenticationPrincipal CustomUserDetails user,@PathVariable long resultId,@PathVariable long issuedId,
            @RequestHeader("Idempotency-Key")String key,@Valid @RequestBody SupportRequests.Answer body){
        var reply=service.answer(user.getUserId(),resultId,issuedId,key,body.getExpectedSupportRevision(),body.getOptionCode(),body.getClientMutationId());
        return ResponseEntity.status(reply.status()).body(json.responseValue(reply.body()));
    }
}
