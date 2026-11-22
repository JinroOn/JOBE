package com.jinroon.jobe.domain.result.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 공유 링크(비로그인 공개)용 전공별 점수. 결과·점수 행 ID는 포함하지 않는다.
 */
@Schema(description = "공유용 전공별 점수")
public record SharedMajorScoreResponse(
        Long majorId,
        @Schema(description = "기존 저장 순서", example = "2") Integer rank,
        @Schema(description = "공동 순위를 반영한 화면 표시 순위", example = "1") Integer displayRank,
        @Schema(description = "해당 표시 순위의 전공 수", example = "2") int tieCount,
        Float finalScore,
        Float tendencyScore,
        Float competencyScore,
        Boolean failed,
        String strengths,
        String weaknesses,
        String recommendationReason
) {
    public static SharedMajorScoreResponse from(RankedMajorScore ranked) {
        var score = ranked.score();
        return new SharedMajorScoreResponse(
                score.getMajorId(),
                score.getRank(),
                ranked.displayRank(),
                ranked.tieCount(),
                score.getFinalScore(),
                score.getTendencyScore(),
                score.getCompetencyScore(),
                score.getFailed(),
                score.getStrengths(),
                score.getWeaknesses(),
                score.getRecommendationReason()
        );
    }
}
