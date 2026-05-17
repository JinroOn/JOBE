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
@Table(name = "competency_eval_results")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CompetencyEvalResult extends BaseEntitySupport {

    @Column(name = "diagnosis_session_id", nullable = false)
    private Long diagnosisSessionId;

    @Column(name = "math_logic", nullable = false)
    private Float mathLogic;

    @Column(name = "problem_solving", nullable = false)
    private Float problemSolving;

    @Column(name = "info_tech", nullable = false)
    private Float infoTech;

    @Column(nullable = false)
    private Float implementation;

    @Column(name = "system_understanding", nullable = false)
    private Float systemUnderstanding;

    @Column(name = "data_analysis", nullable = false)
    private Float dataAnalysis;

    @Column(nullable = false)
    private Float communication;

    @Column(nullable = false)
    private Float collaboration;

    @Column(name = "self_management", nullable = false)
    private Float selfManagement;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
