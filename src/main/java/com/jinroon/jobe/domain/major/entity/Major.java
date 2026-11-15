package com.jinroon.jobe.domain.major.entity;

import com.jinroon.jobe.global.common.entity.BaseEntitySupport;
import com.jinroon.jobe.domain.major.enums.MajorEnums.MajorDifficulty;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "majors", indexes = @Index(name = "idx_majors_category", columnList = "category"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Major extends BaseEntitySupport {

    public void updateDescription(String description, String careerPaths) {
        if (description != null) this.description = description;
        if (careerPaths != null) this.careerPaths = careerPaths;
    }

    public void updateLegacyMetadata(String name, String category, MajorDifficulty difficulty) {
        if (name != null) this.name = name;
        if (category != null) this.category = category;
        if (difficulty != null) this.difficulty = difficulty;
    }

    public record LegacyScores(Float reqMathLogic,
        Float reqProblemSolving,
        Float reqInfoTech,
        Float reqImplementation,
        Float reqSystemUnderstanding,
        Float reqDataAnalysis,
        Float reqCommunication,
        Float reqCollaboration,
        Float reqSelfManagement,
        Float tendLogicalInquiry,
        Float tendPracticalTech,
        Float tendArtCreative,
        Float tendSocialCooperation,
        Float tendLifeHealth,
        Float tendEducationGuide,
        Float tendTheoryAcademic,
        Float tendDataAnalytics,
        Float tendSystemOperation,
        Float thrMathLogic,
        Float thrInfoTech) {}

    public void updateLegacyScores(LegacyScores scores) {
        if (scores.reqMathLogic() != null) this.reqMathLogic = scores.reqMathLogic();
        if (scores.reqProblemSolving() != null) this.reqProblemSolving = scores.reqProblemSolving();
        if (scores.reqInfoTech() != null) this.reqInfoTech = scores.reqInfoTech();
        if (scores.reqImplementation() != null) this.reqImplementation = scores.reqImplementation();
        if (scores.reqSystemUnderstanding() != null) this.reqSystemUnderstanding = scores.reqSystemUnderstanding();
        if (scores.reqDataAnalysis() != null) this.reqDataAnalysis = scores.reqDataAnalysis();
        if (scores.reqCommunication() != null) this.reqCommunication = scores.reqCommunication();
        if (scores.reqCollaboration() != null) this.reqCollaboration = scores.reqCollaboration();
        if (scores.reqSelfManagement() != null) this.reqSelfManagement = scores.reqSelfManagement();
        if (scores.tendLogicalInquiry() != null) this.tendLogicalInquiry = scores.tendLogicalInquiry();
        if (scores.tendPracticalTech() != null) this.tendPracticalTech = scores.tendPracticalTech();
        if (scores.tendArtCreative() != null) this.tendArtCreative = scores.tendArtCreative();
        if (scores.tendSocialCooperation() != null) this.tendSocialCooperation = scores.tendSocialCooperation();
        if (scores.tendLifeHealth() != null) this.tendLifeHealth = scores.tendLifeHealth();
        if (scores.tendEducationGuide() != null) this.tendEducationGuide = scores.tendEducationGuide();
        if (scores.tendTheoryAcademic() != null) this.tendTheoryAcademic = scores.tendTheoryAcademic();
        if (scores.tendDataAnalytics() != null) this.tendDataAnalytics = scores.tendDataAnalytics();
        if (scores.tendSystemOperation() != null) this.tendSystemOperation = scores.tendSystemOperation();
        if (scores.thrMathLogic() != null) this.thrMathLogic = scores.thrMathLogic();
        if (scores.thrInfoTech() != null) this.thrInfoTech = scores.thrInfoTech();
    }

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 50)
    private String category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MajorDifficulty difficulty;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String description;

    @Lob
    @Column(name = "career_paths", columnDefinition = "TEXT")
    private String careerPaths;

    @Column(name = "req_math_logic", nullable = false)
    private Float reqMathLogic = 0.0f;

    @Column(name = "req_problem_solving", nullable = false)
    private Float reqProblemSolving = 0.0f;

    @Column(name = "req_info_tech", nullable = false)
    private Float reqInfoTech = 0.0f;

    @Column(name = "req_implementation", nullable = false)
    private Float reqImplementation = 0.0f;

    @Column(name = "req_system_understanding", nullable = false)
    private Float reqSystemUnderstanding = 0.0f;

    @Column(name = "req_data_analysis", nullable = false)
    private Float reqDataAnalysis = 0.0f;

    @Column(name = "req_communication", nullable = false)
    private Float reqCommunication = 0.0f;

    @Column(name = "req_collaboration", nullable = false)
    private Float reqCollaboration = 0.0f;

    @Column(name = "req_self_management", nullable = false)
    private Float reqSelfManagement = 0.0f;

    @Column(name = "tend_logical_inquiry", nullable = false)
    private Float tendLogicalInquiry = 0.0f;

    @Column(name = "tend_practical_tech", nullable = false)
    private Float tendPracticalTech = 0.0f;

    @Column(name = "tend_art_creative", nullable = false)
    private Float tendArtCreative = 0.0f;

    @Column(name = "tend_social_cooperation", nullable = false)
    private Float tendSocialCooperation = 0.0f;

    @Column(name = "tend_life_health", nullable = false)
    private Float tendLifeHealth = 0.0f;

    @Column(name = "tend_education_guide", nullable = false)
    private Float tendEducationGuide = 0.0f;

    @Column(name = "tend_theory_academic", nullable = false)
    private Float tendTheoryAcademic = 0.0f;

    @Column(name = "tend_data_analytics", nullable = false)
    private Float tendDataAnalytics = 0.0f;

    @Column(name = "tend_system_operation", nullable = false)
    private Float tendSystemOperation = 0.0f;

    @Column(name = "thr_math_logic")
    private Float thrMathLogic = 0.0f;

    @Column(name = "thr_info_tech")
    private Float thrInfoTech = 0.0f;
}
