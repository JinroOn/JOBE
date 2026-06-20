package com.jinroon.jobe.global.client.dto.request;

import java.util.List;
import java.util.Map;

public record ConsultationChatRequest(
        Long sessionId,
        Long userId,
        String userMessage,
        List<HistoryMessage> history,
        DiagnosisContext diagnosisContext
) {
    public record HistoryMessage(
            String role,
            String content
    ) {
    }

    public record DiagnosisContext(
            Long diagnosisResultId,
            boolean usedLatestDiagnosisResult,
            String competencyVector,
            String tendencyVector,
            String aiComment,
            List<String> weaknessFocus,
            List<TopMajor> topMajors,
            List<PlanContext> plans
    ) {
    }

    public record TopMajor(
            Long majorId,
            String majorName,
            Integer rank,
            Float finalScore,
            Float competencyScore,
            Float tendencyScore,
            Boolean failed,
            String strengths,
            String weaknesses,
            String recommendationReason,
            MajorContext majorContext
    ) {
    }

    public record MajorContext(
            String category,
            String description,
            String careerPaths,
            Map<String, Float> requiredCompetencies
    ) {
    }

    public record PlanContext(
            Long planId,
            Long resultMajorScoreId,
            String overview,
            Boolean activeVersion,
            List<PlanItem> items
    ) {
    }

    public record PlanItem(
            Integer weekNo,
            String goal,
            String tasksJson,
            String resourcesJson,
            String checkpoint,
            Boolean completed
    ) {
    }
}
