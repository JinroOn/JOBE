package com.jinroon.jobe.domain.exploration.review;

import com.jinroon.jobe.domain.exploration.review.CatalogReviewService.TargetType;
import com.jinroon.jobe.global.security.CustomUserDetails;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * 관리자 전용(/api/admin/**). 검토(#98)·활성화(#99) 대상을 고르는 목록과 상세.
 * 대상은 검토·활성화 API와 같은 targetType·targetId로 식별한다. 테이블마다 id가 따로 매겨지기 때문이다.
 */
@RestController
@RequestMapping("/api/admin/exploration/catalogs")
@RequiredArgsConstructor
public class AdminCatalogQueryController {

    private final AdminCatalogQueryService catalogs;

    @GetMapping
    public List<AdminCatalogQueryService.CatalogSummary> list(
            @AuthenticationPrincipal CustomUserDetails admin,
            @RequestParam(required = false) TargetType targetType,
            @RequestParam(required = false) @Pattern(regexp = "DRAFT|REVIEWED|ACTIVE|ARCHIVED") String contentStatus) {
        return catalogs.list(admin.getUserId(), targetType, contentStatus);
    }

    @GetMapping("/{targetType}/{targetId}")
    public AdminCatalogQueryService.CatalogDetail detail(@AuthenticationPrincipal CustomUserDetails admin,
                                                         @PathVariable TargetType targetType,
                                                         @PathVariable @Positive long targetId) {
        return catalogs.detail(admin.getUserId(), targetType, targetId);
    }

    @GetMapping("/ACTIVITY_CATALOG/{catalogId}/activities")
    public List<AdminCatalogQueryService.ActivitySummary> activities(@PathVariable @Positive long catalogId) {
        return catalogs.activities(catalogId);
    }

    @GetMapping("/ACTIVITY_CATALOG/{catalogId}/activities/{activityId}")
    public AdminCatalogQueryService.ActivityDetail activity(@PathVariable @Positive long catalogId,
                                                             @PathVariable @Positive long activityId) {
        return catalogs.activity(catalogId, activityId);
    }
}
