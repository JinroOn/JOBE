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
@Table(name = "diagnosis_exam_answers")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DiagnosisExamAnswer extends BaseEntitySupport {

    @Column(name = "diagnosis_session_id", nullable = false)
    private Long diagnosisSessionId;

    @Column(name = "exam_question_id", nullable = false)
    private Long examQuestionId;

    @Column(name = "selected_answer", length = 1)
    private String selectedAnswer;

    @Column(name = "is_correct", nullable = false)
    private Boolean correct;

    @Column(name = "response_sec")
    private Integer responseSec;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
