package com.jinroon.jobe.global.client.dto.response;

import java.util.List;

public record WeeklyPlanResponse(
        String planId,
        String overview,
        List<WeeklyPlan> weeklyPlan,
        List<String> riskNotes,
        String version,
        String requestId,
        Boolean fallback,
        List<String> fallbackReasons
) {
    public WeeklyPlanResponse(String planId,String overview,List<WeeklyPlan> weeklyPlan,List<String> riskNotes,String version,String requestId){
        this(planId,overview,weeklyPlan,riskNotes,version,requestId,false,List.of());
    }
    public record WeeklyPlan(
            Integer week,
            String goal,
            List<String> tasks,
            List<String> recommendedResources,
            String checkpoint
    ) {
    }
}
