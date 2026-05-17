package com.jinroon.jobe.plan.controller;

import com.jinroon.jobe.plan.domain.MajorWeeklyPlan;
import com.jinroon.jobe.plan.domain.MajorWeeklyPlanItem;
import com.jinroon.jobe.plan.domain.MajorWeeklyPlanRiskNote;
import com.jinroon.jobe.plan.service.PlanService;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
public class PlanController {

    private final PlanService planService;

    @GetMapping("/{planId}")
    public MajorWeeklyPlan getPlan(@PathVariable Long planId) {
        return planService.getPlan(planId);
    }

    @GetMapping("/results/{resultId}")
    public List<MajorWeeklyPlan> findPlansByResult(@PathVariable Long resultId) {
        return planService.findPlansByResult(resultId);
    }

    @GetMapping("/{planId}/items")
    public List<MajorWeeklyPlanItem> findItems(@PathVariable Long planId) {
        return planService.findItems(planId);
    }

    @GetMapping("/{planId}/risk-notes")
    public List<MajorWeeklyPlanRiskNote> findRiskNotes(@PathVariable Long planId) {
        return planService.findRiskNotes(planId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MajorWeeklyPlan createPlan(@RequestBody Map<String, Object> request) {
        return planService.createPlan(request);
    }

    @PatchMapping("/{planId}")
    public MajorWeeklyPlan updatePlan(@PathVariable Long planId, @RequestBody Map<String, Object> request) {
        return planService.updatePlan(planId, request);
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public MajorWeeklyPlanItem createItem(@RequestBody Map<String, Object> request) {
        return planService.createItem(request);
    }

    @PostMapping("/risk-notes")
    @ResponseStatus(HttpStatus.CREATED)
    public MajorWeeklyPlanRiskNote createRiskNote(@RequestBody Map<String, Object> request) {
        return planService.createRiskNote(request);
    }
}
