package com.jinroon.jobe.domain.exploration.api;

import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import com.jinroon.jobe.domain.exploration.service.ExplorationSessionService;
import com.jinroon.jobe.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/exploration/v1/results")
public class ExplorationResultController {
    private final ExplorationSessionService service;
    private final ExplorationJson json;
    private final com.jinroon.jobe.domain.exploration.candidate.CandidateViewService candidates;

    @GetMapping("/{resultId}")
    public Object result(@AuthenticationPrincipal CustomUserDetails user, @PathVariable long resultId) {
        return json.responseValue(service.resultById(user.getUserId(), resultId));
    }

    @GetMapping("/{resultId}/candidates")
    public org.springframework.http.ResponseEntity<Object> candidates(@AuthenticationPrincipal CustomUserDetails user,
            @PathVariable long resultId, jakarta.servlet.http.HttpServletRequest request) {
        if(request.getQueryString()!=null)throw new ExplorationApiException(400,"INVALID_REQUEST","쿼리 파라미터를 지원하지 않습니다.");
        return org.springframework.http.ResponseEntity.ok().header("Cache-Control","private, no-store")
                .body(json.responseValue(candidates.get(user.getUserId(),resultId)));
    }
}
