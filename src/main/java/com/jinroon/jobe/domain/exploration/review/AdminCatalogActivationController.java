package com.jinroon.jobe.domain.exploration.review;

import com.jinroon.jobe.domain.exploration.review.CatalogActivationService.Scope;
import com.jinroon.jobe.domain.exploration.review.CatalogReviewService.TargetType;
import com.jinroon.jobe.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 관리자 전용(/api/admin/**). 활성화·보관을 실행한 관리자를 이력에 기록한다. */
@RestController
@RequestMapping("/api/admin/exploration/activations")
@RequiredArgsConstructor
public class AdminCatalogActivationController {

    private final CatalogActivationService activations;

    public record ActivateRequest(@NotNull TargetType targetType, @NotNull @Positive Long targetId, @NotNull Scope scope,
                                  @NotBlank @Size(max = 2000) String reason) {}

    public record ArchiveRequest(@NotNull TargetType targetType, @NotNull @Positive Long targetId,
                                 @NotBlank @Size(max = 2000) String reason) {}

    @PostMapping
    public CatalogActivationService.Result activate(@AuthenticationPrincipal CustomUserDetails admin,
                                                    @Valid @RequestBody ActivateRequest request) {
        return activations.activate(admin.getUserId(), request.targetType(), request.targetId(), request.scope(), request.reason());
    }

    @PostMapping("/archive")
    public CatalogActivationService.Result archive(@AuthenticationPrincipal CustomUserDetails admin,
                                                   @Valid @RequestBody ArchiveRequest request) {
        return activations.archive(admin.getUserId(), request.targetType(), request.targetId(), request.reason());
    }

    @GetMapping
    public CatalogActivationService.History history(@RequestParam TargetType targetType, @RequestParam @Positive long targetId) {
        return activations.history(targetType, targetId);
    }
}
