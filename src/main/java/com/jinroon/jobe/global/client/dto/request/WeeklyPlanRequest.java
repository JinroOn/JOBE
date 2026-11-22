package com.jinroon.jobe.global.client.dto.request;

import java.util.List;

public record WeeklyPlanRequest(
        Long sessionId,
        TargetMajor targetMajor,
        List<String> weaknessFocus,
        Profile profile,
        Constraints constraints,
        DiagnosisProfileContext profileContext
) {
    public record TargetMajor(
            String majorName,
            Double fitScore,
            MajorContext majorContext
    ) {
    }

    public record MajorContext(
            String category,
            String description,
            String sourceSummary,
            List<String> relatedJobs,
            List<String> ragSnippets
    ) {
    }

    public record Profile(
            Integer mathLogicalScore,
            Integer problemSolvingScore,
            Integer infoTechUtilizationScore,
            Integer softwareImplementationScore,
            Integer systemUnderstandingScore,
            Integer dataAnalysisScore,
            Integer communicationScore,
            Integer collaborationScore,
            Integer selfManagementScore
    ) {
    }

    public record Constraints(
            Integer weeks,
            Integer studyHoursPerWeek,
            String preferredStyle
    ) {
    }
}
