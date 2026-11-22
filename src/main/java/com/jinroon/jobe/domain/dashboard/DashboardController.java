package com.jinroon.jobe.domain.dashboard;

import com.jinroon.jobe.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 로그인한 사용자 본인의 대시보드 요약만 돌려준다. */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardSummaryService dashboard;

    @GetMapping("/summary")
    public DashboardSummaryService.Summary summary(@AuthenticationPrincipal CustomUserDetails user) {
        return dashboard.summary(user.getUserId());
    }
}
