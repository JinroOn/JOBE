package com.jinroon.jobe.major.domain;

import com.jinroon.jobe.common.domain.BaseEntitySupport;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "majors", indexes = @Index(name = "idx_majors_category", columnList = "category"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Major extends BaseEntitySupport {

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
    private Float reqMathLogic;

    @Column(name = "req_problem_solving", nullable = false)
    private Float reqProblemSolving;

    @Column(name = "req_info_tech", nullable = false)
    private Float reqInfoTech;

    @Column(name = "req_implementation", nullable = false)
    private Float reqImplementation;

    @Column(name = "req_system_understanding", nullable = false)
    private Float reqSystemUnderstanding;

    @Column(name = "req_data_analysis", nullable = false)
    private Float reqDataAnalysis;

    @Column(name = "req_communication", nullable = false)
    private Float reqCommunication;

    @Column(name = "req_collaboration", nullable = false)
    private Float reqCollaboration;

    @Column(name = "req_self_management", nullable = false)
    private Float reqSelfManagement;

    @Column(name = "tend_logical_inquiry", nullable = false)
    private Float tendLogicalInquiry;

    @Column(name = "tend_practical_tech", nullable = false)
    private Float tendPracticalTech;

    @Column(name = "tend_art_creative", nullable = false)
    private Float tendArtCreative;

    @Column(name = "tend_social_cooperation", nullable = false)
    private Float tendSocialCooperation;

    @Column(name = "tend_life_health", nullable = false)
    private Float tendLifeHealth;

    @Column(name = "tend_education_guide", nullable = false)
    private Float tendEducationGuide;

    @Column(name = "tend_theory_academic", nullable = false)
    private Float tendTheoryAcademic;

    @Column(name = "tend_data_analytics", nullable = false)
    private Float tendDataAnalytics;

    @Column(name = "tend_system_operation", nullable = false)
    private Float tendSystemOperation;

    @Column(name = "thr_math_logic")
    private Float thrMathLogic;

    @Column(name = "thr_info_tech")
    private Float thrInfoTech;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
