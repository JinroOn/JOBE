package com.jinroon.jobe.global.client.dto.request;

import java.util.List;

public record RecommendationCommentRequest(
        Long sessionId,
        Profile profile,
        List<TopMajor> topMajors,
        List<RecommendationGroup> recommendationGroups,
        UserContext userContext
) {
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

    public record TopMajor(
            String majorName,
            Integer rankingOrder,
            Double fitScore,
            String strengths,
            String weaknesses,
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

    public record RecommendationGroup(
            Integer groupOrder,
            String representativeMajorName,
            Integer representativeRankingOrder,
            List<String> similarMajorNames,
            List<String> commonFitAxes,
            List<DifferencePoint> differencePoints
    ) {
    }

    public record DifferencePoint(
            String majorName,
            String description
    ) {
    }

    public record UserContext(
            String grade,
            String careerField,
            String preferredSubject,
            Integer studyHours,
            String learningStyle,
            Integer theoryPreference,
            Integer practicePreference,
            Integer mathAchievement,
            Integer scienceAchievement,
            String problemSolvingStyle
    ) {
    }
}
