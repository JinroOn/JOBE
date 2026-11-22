package com.jinroon.jobe.domain.result.entity;

import com.jinroon.jobe.global.common.entity.BaseEntitySupport;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "result_major_scores")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ResultMajorScore extends BaseEntitySupport {

    @Column(name = "diagnosis_result_id", nullable = false)
    private Long diagnosisResultId;

    @Column(name = "major_id", nullable = false)
    private Long majorId;

    @Column(name = "tendency_score")
    private Float tendencyScore;

    @Column(name = "competency_score")
    private Float competencyScore;

    @Column(name = "final_score")
    private Float finalScore;

    @Column(name = "`rank`")
    private Integer rank;

    @Column(name = "is_failed", nullable = false)
    private Boolean failed;

    @Column(name = "strengths", columnDefinition = "TEXT")
    private String strengths;

    @Column(name = "weaknesses", columnDefinition = "TEXT")
    private String weaknesses;

    @Column(name = "recommendation_reason", columnDefinition = "TEXT")
    private String recommendationReason;

    public static ResultMajorScore of(
            Long diagnosisResultId,
            Long majorId,
            Float tendencyScore,
            Float competencyScore,
            Float finalScore,
            Integer rank,
            Boolean failed,
            String recommendationReason
    ) {
        ResultMajorScore score = new ResultMajorScore();
        score.diagnosisResultId = diagnosisResultId;
        score.majorId = majorId;
        score.tendencyScore = tendencyScore;
        score.competencyScore = competencyScore;
        score.finalScore = finalScore;
        score.rank = rank;
        score.failed = failed;
        score.recommendationReason = recommendationReason;
        return score;
    }

    public void applyAiComment(String strengths, String weaknesses, String recommendationReason) {
        this.strengths = strengths;
        this.weaknesses = weaknesses;
        this.recommendationReason = recommendationReason;
    }

    public void assignRank(Integer rank) {
        this.rank = rank;
    }

    public void applyInitialRecommendationReason(String recommendationReason) {
        if (this.recommendationReason == null || this.recommendationReason.isBlank()) {
            this.recommendationReason = recommendationReason;
        }
    }
}
