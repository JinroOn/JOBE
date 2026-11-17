package com.jinroon.jobe.domain.exploration.api;
import com.jinroon.jobe.domain.exploration.plan.*;
import com.jinroon.jobe.domain.exploration.ai.ExplorationJobClient;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import com.jinroon.jobe.global.security.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/exploration/v1")
public class ExplorationPlanController {
    private final ExplorationPlanService service;
    private final ExplorationPlanStore plans;
    private final ExplorationJson json;
    @PostMapping("/results/{resultId}/plans")
    public Object create(@AuthenticationPrincipal CustomUserDetails user,@PathVariable long resultId,@RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> body,HttpServletRequest request){
        noQuery(request);
        if(body==null||body.size()!=2||!body.containsKey("supportRevision"))throw invalid();
        String field=body.containsKey("majorCode")?"majorCode":"targetMajorCode";
        if(!(body.get(field) instanceof String)||body.keySet().stream().anyMatch(k->!Set.of(field,"supportRevision").contains(k)))throw invalid();
        Object revision=body.get("supportRevision");Long value=null;
        if(revision!=null){if(!(revision instanceof Integer||revision instanceof Long)||((Number)revision).longValue()<0)throw invalid();value=((Number)revision).longValue();}
        try{return json.responseValue(service.create(user.getUserId(),resultId,key,(String)body.get(field),value));}
        catch(ExplorationJobClient.Unavailable e){throw unavailable();}
    }
    @GetMapping("/results/{resultId}/plans") public Object list(@AuthenticationPrincipal CustomUserDetails user,@PathVariable long resultId,HttpServletRequest request){noQuery(request);return json.responseValue(plans.list(user.getUserId(),resultId));}
    @GetMapping("/plans/{planId}") public Object get(@AuthenticationPrincipal CustomUserDetails user,@PathVariable long planId,HttpServletRequest request){noQuery(request);return json.responseValue(plans.get(user.getUserId(),planId));}
    @PostMapping("/plans/{planId}/retry") public Object retry(@AuthenticationPrincipal CustomUserDetails user,@PathVariable long planId,@RequestBody Map<String,Object> body,HttpServletRequest request){
        noQuery(request);if(body==null||!body.isEmpty())throw invalid();
        try{return json.responseValue(service.retry(user.getUserId(),planId));}catch(ExplorationJobClient.Unavailable e){throw unavailable();}
    }
    @DeleteMapping("/plans/{planId}/generation") public Object cancel(@AuthenticationPrincipal CustomUserDetails user,@PathVariable long planId,HttpServletRequest request){
        noQuery(request);try{service.cancel(user.getUserId(),planId);return Map.of("status","CANCELLED");}catch(ExplorationJobClient.Unavailable e){throw unavailable();}
    }
    private void noQuery(HttpServletRequest request){if(!request.getParameterMap().isEmpty())throw invalid();}
    private ExplorationApiException invalid(){return new ExplorationApiException(400,"INVALID_REQUEST","계획 요청 형식을 확인해 주세요.");}
    private ExplorationApiException unavailable(){return new ExplorationApiException(503,"PLAN_GENERATION_UNAVAILABLE","계획 생성 서비스를 사용할 수 없습니다.");}
}
