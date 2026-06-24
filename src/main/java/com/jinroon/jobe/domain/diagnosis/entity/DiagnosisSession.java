package com.jinroon.jobe.domain.diagnosis.entity;

import com.jinroon.jobe.global.common.entity.BaseEntitySupport;
import com.jinroon.jobe.domain.diagnosis.enums.DiagnosisEnums.DiagnosisStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "diagnosis_sessions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DiagnosisSession extends BaseEntitySupport {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DiagnosisStatus status;

    @Column(name = "current_step", nullable = false)
    private Integer currentStep;

    @Column(name = "input_snapshot", columnDefinition = "JSON")
    private String inputSnapshot;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    public void complete(LocalDateTime completedAt) {
        this.status = DiagnosisStatus.completed;
        if (this.completedAt == null) {
            this.completedAt = completedAt;
        }
    }
}
