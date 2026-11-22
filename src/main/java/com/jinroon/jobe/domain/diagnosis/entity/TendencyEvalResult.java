package com.jinroon.jobe.domain.diagnosis.entity;

import com.jinroon.jobe.global.common.entity.BaseEntitySupport;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "tendency_eval_results")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TendencyEvalResult extends BaseEntitySupport {

    @Column(name = "diagnosis_session_id", nullable = false)
    private Long diagnosisSessionId;

    @Column(name = "logical_inquiry", nullable = false)
    private Float logicalInquiry;

    @Column(name = "practical_tech", nullable = false)
    private Float practicalTech;

    @Column(name = "art_creative", nullable = false)
    private Float artCreative;

    @Column(name = "social_cooperation", nullable = false)
    private Float socialCooperation;

    @Column(name = "life_health", nullable = false)
    private Float lifeHealth;

    @Column(name = "education_guide", nullable = false)
    private Float educationGuide;

    @Column(name = "theory_academic", nullable = false)
    private Float theoryAcademic;

    @Column(name = "data_analytics", nullable = false)
    private Float dataAnalytics;

    @Column(name = "system_operation", nullable = false)
    private Float systemOperation;

    public void updateScores(
            Float logicalInquiry,
            Float practicalTech,
            Float artCreative,
            Float socialCooperation,
            Float lifeHealth,
            Float educationGuide,
            Float theoryAcademic,
            Float dataAnalytics,
            Float systemOperation
    ) {
        this.logicalInquiry = clampScore(logicalInquiry);
        this.practicalTech = clampScore(practicalTech);
        this.artCreative = clampScore(artCreative);
        this.socialCooperation = clampScore(socialCooperation);
        this.lifeHealth = clampScore(lifeHealth);
        this.educationGuide = clampScore(educationGuide);
        this.theoryAcademic = clampScore(theoryAcademic);
        this.dataAnalytics = clampScore(dataAnalytics);
        this.systemOperation = clampScore(systemOperation);
    }

    private static Float clampScore(Float value) {
        if (value == null) {
            return 0.0f;
        }
        return Math.max(0.0f, Math.min(100.0f, value));
    }
}
