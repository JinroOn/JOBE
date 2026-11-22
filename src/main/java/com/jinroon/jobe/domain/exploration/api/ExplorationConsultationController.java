package com.jinroon.jobe.domain.exploration.api;

import com.jinroon.jobe.domain.exploration.consultation.*;
import com.jinroon.jobe.global.security.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import com.jinroon.jobe.domain.exploration.ai.ExplorationJobClient;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/exploration/v1")
public class ExplorationConsultationController {
    private final ExplorationConsultationService service;
    private final ConsultationMessageService messages;
    private final ExplorationJson json;
    @DeleteMapping("/consultations/{consultationId}/messages/{retryKey}")
    public Object cancel(@AuthenticationPrincipal CustomUserDetails user,@PathVariable long consultationId,@PathVariable String retryKey,HttpServletRequest request){
        if(!request.getParameterMap().isEmpty())throw new ExplorationApiException(400,"INVALID_REQUEST","취소 요청 형식을 확인해 주세요.");
        try{messages.cancel(user.getUserId(),consultationId,retryKey);return Map.of("status","CANCELLED");}
        catch(ExplorationJobClient.Unavailable e){throw new ExplorationApiException(503,"CONSULTATION_GENERATION_UNAVAILABLE","상담 취소 상태를 확인할 수 없습니다.");}
    }
    @PostMapping("/consultations/{consultationId}/messages")
    public Object message(@AuthenticationPrincipal CustomUserDetails user,@PathVariable long consultationId,
            @RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> body,HttpServletRequest request){
        if(body==null||body.size()!=1||!(body.get("content") instanceof String)||!request.getParameterMap().isEmpty())
            throw new ExplorationApiException(400,"INVALID_REQUEST","질문 본문을 확인해 주세요.");
        try{return json.responseValue(messages.send(user.getUserId(),consultationId,key,(String)body.get("content")));}
        catch(ExplorationJobClient.Conflict e){throw new ExplorationApiException(409,"CONSULTATION_BUSY","상담 요청이 처리 중이거나 충돌합니다.");}
        catch(ExplorationJobClient.Unavailable e){throw new ExplorationApiException(503,"CONSULTATION_GENERATION_UNAVAILABLE","상담 생성 서비스를 사용할 수 없습니다.");}
    }
    @PostMapping("/results/{resultId}/consultations")
    public ConsultationDtos.Session open(@AuthenticationPrincipal CustomUserDetails user,@PathVariable long resultId,
            @RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> body,HttpServletRequest request){
        if(body==null||!body.isEmpty()||!request.getParameterMap().isEmpty())
            throw new ExplorationApiException(400,"INVALID_REQUEST","상담 시작은 빈 객체만 허용합니다.");
        return service.open(user.getUserId(),resultId,key);
    }
}
