package com.jinroon.jobe.domain.exploration.api;

import com.jinroon.jobe.domain.exploration.history.DiagnosisHistoryService;
import com.jinroon.jobe.domain.exploration.history.HistoryQuery;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import com.jinroon.jobe.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/exploration/v1/history")
public class DiagnosisHistoryController {
    private final DiagnosisHistoryService history;
    private final ExplorationJson json;

    @GetMapping("/sessions")
    public Object sessions(@AuthenticationPrincipal CustomUserDetails user,@RequestParam MultiValueMap<String,String> query) {
        return json.responseValue(history.list(user.getUserId(),"sessions",HistoryQuery.parse(query)));
    }
    @GetMapping("/results")
    public Object results(@AuthenticationPrincipal CustomUserDetails user,@RequestParam MultiValueMap<String,String> query) {
        return json.responseValue(history.list(user.getUserId(),"results",HistoryQuery.parse(query)));
    }
}
