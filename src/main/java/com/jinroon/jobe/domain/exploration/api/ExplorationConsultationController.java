package com.jinroon.jobe.domain.exploration.api;

import com.jinroon.jobe.domain.exploration.consultation.*;
import com.jinroon.jobe.global.security.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/exploration/v1")
public class ExplorationConsultationController {
    private final ExplorationConsultationService service;
    @PostMapping("/results/{resultId}/consultations")
    public ConsultationDtos.Session open(@AuthenticationPrincipal CustomUserDetails user,@PathVariable long resultId,
            @RequestHeader("Idempotency-Key") String key,@RequestBody Map<String,Object> body,HttpServletRequest request){
        if(body==null||!body.isEmpty()||!request.getParameterMap().isEmpty())
            throw new ExplorationApiException(400,"INVALID_REQUEST","상담 시작은 빈 객체만 허용합니다.");
        return service.open(user.getUserId(),resultId,key);
    }
}
