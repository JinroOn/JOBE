package com.jinroon.jobe.global.client.dto.response;

import java.util.List;

public record WeeklyPlanResponse(
        String planId,
        String overview,
        List<WeeklyPlan> weeklyPlan,
        String riskNotes,
        String version,
        String requestId
) {
    public record WeeklyPlan(
            Integer week,
            String goal,
            List<String> tasks,
            List<String> recommendedResources,
            String checkpoint
    ) {
    }
}
