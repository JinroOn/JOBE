package com.jinroon.jobe.domain.exploration.api;

import com.jinroon.jobe.domain.exploration.activity.ActivityRecommendationService;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import com.jinroon.jobe.global.security.CustomUserDetails;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/exploration/v1/results/{resultId}/activities")
public class ActivityRecommendationController {
    private final ActivityRecommendationService service;
    private final ExplorationJson json;
    @GetMapping public Object get(@AuthenticationPrincipal CustomUserDetails user,@PathVariable long resultId,HttpServletRequest request) {
        if(!request.getParameterMap().isEmpty())throw new ExplorationApiException(400,"INVALID_REQUEST","활동 버전·대상·승인 조건은 서버가 결정합니다.");
        return json.responseValue(service.get(user.getUserId(),resultId));
    }
}
