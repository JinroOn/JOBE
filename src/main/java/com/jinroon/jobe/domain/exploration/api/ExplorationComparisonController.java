package com.jinroon.jobe.domain.exploration.api;

import com.jinroon.jobe.domain.exploration.comparison.ExplorationComparisonService;
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
public class ExplorationComparisonController {
    private final ExplorationComparisonService service;
    private final ExplorationJson json;
    @GetMapping("/results/{resultId}/comparison")
    public Object result(@AuthenticationPrincipal CustomUserDetails user,@PathVariable long resultId,HttpServletRequest request) {
        var query=request.getParameterMap();
        if(query.isEmpty())return json.responseValue(service.result(user.getUserId(),resultId,null,null));
        if(!query.keySet().equals(Set.of("leftMajorCode","rightMajorCode")))throw invalid();
        return json.responseValue(service.result(user.getUserId(),resultId,parameter(query,"leftMajorCode"),parameter(query,"rightMajorCode")));
    }
    @GetMapping("/sessions/{sessionId}/comparison-options")
    public Object options(@AuthenticationPrincipal CustomUserDetails user,@PathVariable long sessionId,HttpServletRequest request) {
        var query=request.getParameterMap();if(!query.keySet().equals(Set.of("comparisonSet")))throw invalid();
        String set=parameter(query,"comparisonSet");if(!set.matches("CMP-[A-Z0-9]+(-[A-Z0-9]+)*"))throw invalid();
        return json.responseValue(service.options(user.getUserId(),sessionId,set));
    }
    private String parameter(Map<String,String[]> query,String name){String[] values=query.get(name);if(values==null||values.length!=1||!values[0].matches("[!-~]{1,100}"))throw invalid();return values[0];}
    private ExplorationApiException invalid(){return new ExplorationApiException(400,"INVALID_REQUEST","비교 요청 필드와 형식을 확인해 주세요.");}
}
