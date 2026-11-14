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

    /** 새 진단 회차 시작. 상태·시작 시각은 서버가 정한다. */
    public static DiagnosisSession start(Long userId, Integer currentStep, String inputSnapshot) {
        DiagnosisSession session = new DiagnosisSession();
        session.userId = userId;
        session.status = DiagnosisStatus.in_progress;
        session.currentStep = currentStep == null ? 1 : currentStep;
        session.inputSnapshot = inputSnapshot;
        session.startedAt = LocalDateTime.now();
        return session;
    }

    /**
     * 진행 중 회차의 단계·입력값·상태 변경. null이면 기존 값을 유지한다.
     * 완료(completed) 전환은 결과 완료 API(ResultService.completeDiagnosisResultForUser)에서만 한다.
     */
    public void updateProgress(Integer currentStep, String inputSnapshot, DiagnosisStatus status) {
        if (currentStep != null) {
            this.currentStep = currentStep;
        }
        if (inputSnapshot != null) {
            this.inputSnapshot = inputSnapshot;
        }
        if (status != null) {
            this.status = status;
        }
    }

    public boolean isCompleted() {
        return status == DiagnosisStatus.completed;
    }

    public void complete(LocalDateTime completedAt) {
        this.status = DiagnosisStatus.completed;
        if (this.completedAt == null) {
            this.completedAt = completedAt;
        }
    }
}
