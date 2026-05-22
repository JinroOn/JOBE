package com.jinroon.jobe.domain.diagnosis.entity;

import com.jinroon.jobe.global.common.entity.BaseEntitySupport;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "diagnosis_essay_answers")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DiagnosisEssayAnswer extends BaseEntitySupport {

    @Column(name = "diagnosis_session_id", nullable = false)
    private Long diagnosisSessionId;

    @Column(name = "question_no", nullable = false)
    private Integer questionNo;

    @Lob
    @Column(name = "answer_text", nullable = false, columnDefinition = "TEXT")
    private String answerText;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
