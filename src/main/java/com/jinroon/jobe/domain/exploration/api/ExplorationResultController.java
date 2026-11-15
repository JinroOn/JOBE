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

    @GetMapping("/{resultId}")
    public Object result(@AuthenticationPrincipal CustomUserDetails user, @PathVariable long resultId) {
        return json.responseValue(service.resultById(user.getUserId(), resultId));
    }
}
