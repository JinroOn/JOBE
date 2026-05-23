package com.jinroon.jobe.domain.plan.controller;

import com.jinroon.jobe.domain.plan.controller.api.PlanApi;
import com.jinroon.jobe.domain.plan.entity.MajorWeeklyPlan;
import com.jinroon.jobe.domain.plan.entity.MajorWeeklyPlanItem;
import com.jinroon.jobe.domain.plan.entity.MajorWeeklyPlanRiskNote;
import com.jinroon.jobe.domain.plan.service.PlanService;
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
public class PlanController implements PlanApi {

    private final PlanService planService;

    @Override
    @GetMapping("/{planId}")
    public MajorWeeklyPlan getPlan(@PathVariable Long planId) {
        return planService.getPlan(planId);
    }

    @Override
    @GetMapping("/results/{resultId}")
    public List<MajorWeeklyPlan> findPlansByResult(@PathVariable Long resultId) {
        return planService.findPlansByResult(resultId);
    }

    @Override
    @GetMapping("/{planId}/items")
    public List<MajorWeeklyPlanItem> findItems(@PathVariable Long planId) {
        return planService.findItems(planId);
    }

    @Override
    @GetMapping("/{planId}/risk-notes")
    public List<MajorWeeklyPlanRiskNote> findRiskNotes(@PathVariable Long planId) {
        return planService.findRiskNotes(planId);
    }

    @Override
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MajorWeeklyPlan createPlan(@RequestBody Map<String, Object> request) {
        return planService.createPlan(request);
    }

    @Override
    @PatchMapping("/{planId}")
    public MajorWeeklyPlan updatePlan(@PathVariable Long planId, @RequestBody Map<String, Object> request) {
        return planService.updatePlan(planId, request);
    }

    @Override
    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public MajorWeeklyPlanItem createItem(@RequestBody Map<String, Object> request) {
        return planService.createItem(request);
    }

    @Override
    @PostMapping("/risk-notes")
    @ResponseStatus(HttpStatus.CREATED)
    public MajorWeeklyPlanRiskNote createRiskNote(@RequestBody Map<String, Object> request) {
        return planService.createRiskNote(request);
    }
}
