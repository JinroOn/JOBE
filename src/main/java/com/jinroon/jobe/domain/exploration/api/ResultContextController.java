package com.jinroon.jobe.domain.exploration.api;

import com.jinroon.jobe.domain.exploration.context.ResultContextService;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import com.jinroon.jobe.global.security.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/exploration/v1")
public class ResultContextController {
    private final ResultContextService service;
    private final ExplorationJson json;
    @PostMapping("/results/{resultId}/contexts") public ResponseEntity<Object> prepare(@AuthenticationPrincipal CustomUserDetails user,@PathVariable long resultId,@RequestHeader("Idempotency-Key")String key,@Valid @RequestBody ContextRequests.Prepare body,HttpServletRequest request){noQuery(request);var reply=service.prepare(user.getUserId(),resultId,key,body.getContextType(),body.getExpectedSupportRevision(),body.getTargetMajorCode());return ResponseEntity.status(reply.status()).body(json.responseValue(reply.body()));}
    @GetMapping("/contexts/{contextId}") public Object get(@AuthenticationPrincipal CustomUserDetails user,@PathVariable long contextId,HttpServletRequest request){noQuery(request);return json.responseValue(service.get(user.getUserId(),contextId));}
    @GetMapping("/results/{resultId}/contexts") public Object list(@AuthenticationPrincipal CustomUserDetails user,@PathVariable long resultId,HttpServletRequest request){
        var query=request.getParameterMap();if(query.keySet().stream().anyMatch(k->!Set.of("limit","beforeContextId").contains(k)))throw invalid();
        Long limit=positive(query,"limit"),before=positive(query,"beforeContextId");if(limit!=null&&limit>100)throw invalid();return json.responseValue(service.list(user.getUserId(),resultId,limit==null?20:limit.intValue(),before));
    }
    private Long positive(Map<String,String[]> query,String key){if(!query.containsKey(key))return null;String[] values=query.get(key);if(values.length!=1||!values[0].matches("[1-9][0-9]{0,18}"))throw invalid();try{return Long.valueOf(values[0]);}catch(NumberFormatException e){throw invalid();}}
    private void noQuery(HttpServletRequest request){if(!request.getParameterMap().isEmpty())throw invalid();}
    private ExplorationApiException invalid(){return new ExplorationApiException(400,"INVALID_REQUEST","입력 컨텍스트 조회 조건을 확인해 주세요.");}
}
