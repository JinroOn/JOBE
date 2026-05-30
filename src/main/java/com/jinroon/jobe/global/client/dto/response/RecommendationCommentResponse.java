package com.jinroon.jobe.global.client.dto.response;

import java.util.List;

public record RecommendationCommentResponse(
        String summaryComment,
        List<MajorComment> majorComments,
        List<String> weaknessFocus,
        String version,
        String requestId
) {
    public record MajorComment(
            String majorName,
            Integer rankingOrder,
            Double fitScore,
            String strengths,
            String weaknesses,
            String recommendationReason
    ) {
    }
}
