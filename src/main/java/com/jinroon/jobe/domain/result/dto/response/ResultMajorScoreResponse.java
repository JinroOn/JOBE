package com.jinroon.jobe.domain.result.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "진단 결과 전공별 점수")
public record ResultMajorScoreResponse(
        Long id,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Long diagnosisResultId,
        Long majorId,
        Float tendencyScore,
        Float competencyScore,
        Float finalScore,
        @Schema(description = "기존 저장 순서", example = "2") Integer rank,
        @Schema(description = "공동 순위를 반영한 화면 표시 순위", example = "1") Integer displayRank,
        @Schema(description = "해당 표시 순위의 전공 수", example = "2") int tieCount,
        Boolean failed,
        String strengths,
        String weaknesses,
        String recommendationReason
) {
    public static ResultMajorScoreResponse from(RankedMajorScore ranked) {
        var score = ranked.score();
        return new ResultMajorScoreResponse(
                score.getId(), score.getCreatedAt(), score.getUpdatedAt(), score.getDiagnosisResultId(),
                score.getMajorId(), score.getTendencyScore(), score.getCompetencyScore(), score.getFinalScore(),
                score.getRank(), ranked.displayRank(), ranked.tieCount(), score.getFailed(), score.getStrengths(),
                score.getWeaknesses(), score.getRecommendationReason()
        );
    }
}
