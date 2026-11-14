package com.jinroon.jobe.domain.diagnosis.entity;

import com.jinroon.jobe.global.common.entity.BaseEntitySupport;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
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

    // 채점 후 정오가 응답에 노출되면 다음 회차에서 정답을 알 수 있으므로 내려보내지 않는다.
    @JsonIgnore
    @Column(name = "is_correct", nullable = false)
    private Boolean correct;

    @Column(name = "response_sec")
    private Integer responseSec;

    public void updateSubmission(String selectedAnswer, Integer responseSec) {
        this.selectedAnswer = normalizeAnswer(selectedAnswer);
        this.responseSec = responseSec;
        this.correct = false;
    }

    public void markCorrect(Boolean correct) {
        this.correct = correct;
    }

    private static String normalizeAnswer(String selectedAnswer) {
        if (selectedAnswer == null || selectedAnswer.isBlank()) {
            return null;
        }
        return selectedAnswer.trim().toUpperCase();
    }
}
