package com.jinroon.jobe.diagnosis.domain;

import com.jinroon.jobe.common.domain.BaseEntitySupport;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "exam_questions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExamQuestion extends BaseEntitySupport {

    @Enumerated(EnumType.STRING)
    @Column(name = "competency_category", nullable = false, length = 40)
    private CompetencyCategory competencyCategory;

    @Lob
    @Column(name = "question_text", nullable = false, columnDefinition = "TEXT")
    private String questionText;

    @Lob
    @Column(name = "option_a", nullable = false, columnDefinition = "TEXT")
    private String optionA;

    @Lob
    @Column(name = "option_b", nullable = false, columnDefinition = "TEXT")
    private String optionB;

    @Lob
    @Column(name = "option_c", nullable = false, columnDefinition = "TEXT")
    private String optionC;

    @Lob
    @Column(name = "option_d", nullable = false, columnDefinition = "TEXT")
    private String optionD;

    @Column(name = "correct_answer", nullable = false, length = 1)
    private String correctAnswer;

    @Column(name = "time_limit_sec", nullable = false)
    private Integer timeLimitSec;

    @Column(name = "w_math_logic", nullable = false)
    private Float wMathLogic;

    @Column(name = "w_problem_solving", nullable = false)
    private Float wProblemSolving;

    @Column(name = "w_info_tech", nullable = false)
    private Float wInfoTech;

    @Column(name = "w_implementation", nullable = false)
    private Float wImplementation;

    @Column(name = "w_system_understanding", nullable = false)
    private Float wSystemUnderstanding;

    @Column(name = "w_data_analysis", nullable = false)
    private Float wDataAnalysis;

    @Column(name = "w_communication", nullable = false)
    private Float wCommunication;

    @Column(name = "w_collaboration", nullable = false)
    private Float wCollaboration;

    @Column(name = "w_self_management", nullable = false)
    private Float wSelfManagement;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
