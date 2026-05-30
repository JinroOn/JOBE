package com.jinroon.jobe.global.client.dto.request;

import java.util.List;
import java.util.Map;

public record WeeklyPlanRequest(
        Long sessionId,
        TargetMajor targetMajor,
        List<String> weaknessFocus,
        Map<String, Double> profile,
        Constraints constraints
) {
    public record TargetMajor(
            String majorName,
            String majorCode
    ) {
    }

    public record Constraints(
            Integer weekCount,
            Double weeklyHours
    ) {
    }
}
