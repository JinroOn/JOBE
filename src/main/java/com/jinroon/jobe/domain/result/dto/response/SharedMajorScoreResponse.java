package com.jinroon.jobe.domain.result.dto.response;

import com.jinroon.jobe.domain.result.entity.ResultMajorScore;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 공유 링크(비로그인 공개)용 전공별 점수. 결과·점수 행 ID는 포함하지 않는다.
 */
@Schema(description = "공유용 전공별 점수")
public record SharedMajorScoreResponse(
        Long majorId,
        Integer rank,
        Float finalScore,
        Float tendencyScore,
        Float competencyScore,
        Boolean failed,
        String strengths,
        String weaknesses,
        String recommendationReason
) {
    public static SharedMajorScoreResponse from(ResultMajorScore score) {
        return new SharedMajorScoreResponse(
                score.getMajorId(),
                score.getRank(),
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
