package com.jinroon.jobe.domain.exploration.api;

import com.jinroon.jobe.domain.exploration.profile.ExplorationMajorProfileService;
import com.jinroon.jobe.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 결과 화면의 "전공 자세히 보기"(#101). 탐색형 실행 범위의 사용자만 조회한다. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/exploration/v1/majors")
public class ExplorationMajorProfileController {

    private final ExplorationMajorProfileService profiles;

    @GetMapping("/{majorCode}/profile")
    public ExplorationMajorProfileService.Profile profile(@AuthenticationPrincipal CustomUserDetails user,
                                                          @PathVariable String majorCode) {
        return profiles.profile(user.getUserId(), majorCode);
    }
}
