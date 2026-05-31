package com.jinroon.jobe.domain.plan.controller;

import com.jinroon.jobe.domain.plan.controller.api.PlanApi;
import com.jinroon.jobe.domain.plan.dto.request.MajorWeeklyPlanItemRequest;
import com.jinroon.jobe.domain.plan.dto.request.MajorWeeklyPlanRequest;
import com.jinroon.jobe.domain.plan.dto.request.MajorWeeklyPlanRiskNoteRequest;
import com.jinroon.jobe.domain.plan.entity.MajorWeeklyPlan;
import com.jinroon.jobe.domain.plan.entity.MajorWeeklyPlanItem;
import com.jinroon.jobe.domain.plan.entity.MajorWeeklyPlanRiskNote;
import com.jinroon.jobe.domain.plan.service.PlanService;
import com.jinroon.jobe.global.common.dto.RequestMapMapper;
import com.jinroon.jobe.global.security.CustomUserDetails;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/plans")
public class PlanController implements PlanApi {

    private final PlanService planService;

    @Override
    @GetMapping("/{planId}")
    public MajorWeeklyPlan getPlan(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long planId) {
        return planService.getPlanForUser(planId, userDetails.getUserId());
    }

    @Override
    @GetMapping("/results/{resultId}")
    public List<MajorWeeklyPlan> findPlansByResult(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long resultId) {
        return planService.findPlansByResultForUser(resultId, userDetails.getUserId());
    }

    @Override
    @GetMapping("/{planId}/items")
    public List<MajorWeeklyPlanItem> findItems(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long planId) {
        return planService.findItemsForUser(planId, userDetails.getUserId());
    }

    @Override
    @GetMapping("/{planId}/risk-notes")
    public List<MajorWeeklyPlanRiskNote> findRiskNotes(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long planId) {
        return planService.findRiskNotesForUser(planId, userDetails.getUserId());
    }

    @Override
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MajorWeeklyPlan createPlan(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody MajorWeeklyPlanRequest request) {
        return planService.createPlanForUser(RequestMapMapper.toMap(request), userDetails.getUserId());
    }

    @Override
    @PatchMapping("/{planId}")
    public MajorWeeklyPlan updatePlan(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PathVariable Long planId,
            @Valid @RequestBody MajorWeeklyPlanRequest request) {
        return planService.updatePlanForUser(planId, RequestMapMapper.toMap(request), userDetails.getUserId());
    }

    @Override
    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public MajorWeeklyPlanItem createItem(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody MajorWeeklyPlanItemRequest request) {
        return planService.createItemForUser(RequestMapMapper.toMap(request), userDetails.getUserId());
    }

    @Override
    @PatchMapping("/items/{id}/complete")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void completeItem(
        @PathVariable Long id,
        @AuthenticationPrincipal CustomUserDetails userDetails) {
        planService.completeItemForUser(id, userDetails.getUserId());
    }

    @Override
    @PostMapping("/risk-notes")
    @ResponseStatus(HttpStatus.CREATED)
    public MajorWeeklyPlanRiskNote createRiskNote(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody MajorWeeklyPlanRiskNoteRequest request) {
        return planService.createRiskNoteForUser(RequestMapMapper.toMap(request), userDetails.getUserId());
    }
}
