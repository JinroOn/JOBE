package com.jinroon.jobe.diagnosis.domain;

import com.jinroon.jobe.common.domain.BaseEntitySupport;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
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

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
