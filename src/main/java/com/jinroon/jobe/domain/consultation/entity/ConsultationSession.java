package com.jinroon.jobe.domain.consultation.entity;

import com.jinroon.jobe.global.common.entity.BaseEntitySupport;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "consultation_sessions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConsultationSession extends BaseEntitySupport {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "diagnosis_result_id")
    private Long diagnosisResultId;

    @Column(length = 200)
    private String title;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    public void end() {
        this.endedAt = LocalDateTime.now();
    }

    public boolean isEnded() {
        return endedAt != null;
    }
}
