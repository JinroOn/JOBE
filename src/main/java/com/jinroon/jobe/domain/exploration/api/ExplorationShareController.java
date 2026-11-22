package com.jinroon.jobe.domain.exploration.api;

import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import com.jinroon.jobe.domain.exploration.sharing.ExplorationShareService;
import com.jinroon.jobe.global.security.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/exploration/v1")
public class ExplorationShareController {
    private final ExplorationShareService service;
    private final ExplorationJson json;
    @GetMapping("/results/{resultId}/share") public Object state(@AuthenticationPrincipal CustomUserDetails user,@PathVariable long resultId,HttpServletRequest request){noQuery(request);return json.responseValue(service.get(user.getUserId(),resultId));}
    @PostMapping("/results/{resultId}/share") public ResponseEntity<Object> issue(@AuthenticationPrincipal CustomUserDetails user,@PathVariable long resultId,@RequestHeader("Idempotency-Key")String key,@Valid @RequestBody ShareRequests.Issue body,HttpServletRequest request){noQuery(request);return reply(service.issue(user.getUserId(),resultId,key,body.getExpectedShareRevision(),body.isIncludeNickname()));}
    @PostMapping("/results/{resultId}/share/revoke") public ResponseEntity<Object> revoke(@AuthenticationPrincipal CustomUserDetails user,@PathVariable long resultId,@RequestHeader("Idempotency-Key")String key,@Valid @RequestBody ShareRequests.Mutation body,HttpServletRequest request){noQuery(request);return reply(service.revoke(user.getUserId(),resultId,key,body.getExpectedShareRevision()));}
    @GetMapping("/shared-results/{token}") public Object read(@PathVariable String token,HttpServletRequest request){
        for(String header:java.util.List.of("Forwarded","X-Forwarded-For","X-Real-IP","X-Forwarded-Host","X-Forwarded-Proto"))if(request.getHeader(header)!=null)throw new ExplorationApiException(404,"RESOURCE_NOT_FOUND","공유 결과를 찾을 수 없습니다.");
        noQuery(request);return json.responseValue(service.read(token,request.getRemoteAddr()));
    }
    private void noQuery(HttpServletRequest request){if(!request.getParameterMap().isEmpty())throw new ExplorationApiException(400,"INVALID_REQUEST","공유 요청에는 query parameter를 받지 않습니다.");}
    private ResponseEntity<Object> reply(ExplorationShareService.Reply reply){return ResponseEntity.status(reply.status()).body(json.responseValue(reply.body()));}
}
