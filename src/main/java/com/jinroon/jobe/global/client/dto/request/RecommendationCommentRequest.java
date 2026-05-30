package com.jinroon.jobe.global.client.dto.request;

import java.util.List;
import java.util.Map;

public record RecommendationCommentRequest(
        Long sessionId,
        Map<String, Double> profile,
        List<MajorInfo> topMajors,
        String userContext
) {
    public record MajorInfo(
            String majorName,
            Integer rankingOrder,
            Double fitScore,
            String strengths,
            String weaknesses
    ) {
    }
}
