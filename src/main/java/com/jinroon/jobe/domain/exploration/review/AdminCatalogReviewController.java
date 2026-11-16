package com.jinroon.jobe.domain.exploration.review;

import com.jinroon.jobe.domain.exploration.review.CatalogReviewService.Decision;
import com.jinroon.jobe.domain.exploration.review.CatalogReviewService.ReviewKind;
import com.jinroon.jobe.domain.exploration.review.CatalogReviewService.TargetType;
import com.jinroon.jobe.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 관리자 전용(/api/admin/**). 검토자는 로그인한 관리자로 기록한다. */
@RestController
@RequestMapping("/api/admin/exploration/reviews")
@RequiredArgsConstructor
public class AdminCatalogReviewController {

    private final CatalogReviewService reviews;

    public record ReviewRequest(@NotNull TargetType targetType, @NotNull @Positive Long targetId,
                                @NotNull ReviewKind reviewKind, @NotNull Decision decision,
                                @NotBlank @Size(max = 2000) String evidenceReference) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CatalogReviewService.Recorded record(@AuthenticationPrincipal CustomUserDetails admin,
                                                @Valid @RequestBody ReviewRequest request) {
        return reviews.record(admin.getUserId(), request.targetType(), request.targetId(), request.reviewKind(),
                request.decision(), request.evidenceReference());
    }

    @GetMapping
    public CatalogReviewService.History history(@RequestParam TargetType targetType, @RequestParam @Positive long targetId) {
        return reviews.history(targetType, targetId);
    }
}
