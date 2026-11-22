package com.jinroon.jobe.domain.diagnosis.entity;

import com.jinroon.jobe.global.common.entity.BaseEntitySupport;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    public void applyScores(
            Float mathLogic,
            Float problemSolving,
            Float infoTech,
            Float implementation,
            Float systemUnderstanding,
            Float dataAnalysis,
            Float communication,
            Float collaboration,
            Float selfManagement
    ) {
        this.mathLogic = mathLogic;
        this.problemSolving = problemSolving;
        this.infoTech = infoTech;
        this.implementation = implementation;
        this.systemUnderstanding = systemUnderstanding;
        this.dataAnalysis = dataAnalysis;
        this.communication = communication;
        this.collaboration = collaboration;
        this.selfManagement = selfManagement;
    }

    /**
     * 가장 높은 역량보다 낮은 역량을 낮은 순서로 반환한다. 동점은 역량 표시 순서를 따르고,
     * 모든 점수가 같으면 비어 있다. ai-service 종합평의 보완 역량 기준과 같다.
     */
    public List<String> relativelyWeakFields(int limit) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        scores.put("mathLogicalScore", roundedScore(mathLogic));
        scores.put("problemSolvingScore", roundedScore(problemSolving));
        scores.put("infoTechUtilizationScore", roundedScore(infoTech));
        scores.put("softwareImplementationScore", roundedScore(implementation));
        scores.put("systemUnderstandingScore", roundedScore(systemUnderstanding));
        scores.put("dataAnalysisScore", roundedScore(dataAnalysis));
        scores.put("communicationScore", roundedScore(communication));
        scores.put("collaborationScore", roundedScore(collaboration));
        scores.put("selfManagementScore", roundedScore(selfManagement));
        int highest = scores.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        return scores.entrySet().stream()
                .filter(entry -> entry.getValue() < highest)
                .sorted(Map.Entry.comparingByValue())
                .limit(limit)
                .map(Map.Entry::getKey)
                .toList();
    }

    private static int roundedScore(Float value) {
        return value == null ? 0 : Math.max(0, Math.min(100, Math.round(value)));
    }
}
