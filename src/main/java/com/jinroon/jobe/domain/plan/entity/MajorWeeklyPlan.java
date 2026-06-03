package com.jinroon.jobe.domain.plan.entity;

import com.jinroon.jobe.global.common.entity.BaseEntitySupport;
import com.jinroon.jobe.global.common.ai.AiGenerationStatus;

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
@Table(name = "major_weekly_plans")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MajorWeeklyPlan extends BaseEntitySupport {
    private static final int MAX_AI_ERROR_MESSAGE_LENGTH = 500;

    @Column(name = "diagnosis_result_id", nullable = false)
    private Long diagnosisResultId;

    @Column(name = "result_major_score_id", nullable = false)
    private Long resultMajorScoreId;

    @Column(name = "plan_id", length = 100)
    private String planId;

    @Column(name = "version_no", nullable = false)
    private Integer versionNo;

    @Column(name = "parent_plan_id")
    private Long parentPlanId;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String overview;

    @Column(nullable = false)
    private Boolean fallback;

    @Column(name = "active_version", nullable = false)
    private Boolean activeVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_plan_status", nullable = false, length = 30)
    private AiGenerationStatus aiPlanStatus = AiGenerationStatus.NOT_REQUESTED;

    @Column(name = "ai_plan_error_message", length = 500)
    private String aiPlanErrorMessage;

    @Column(name = "ai_plan_requested_at")
    private LocalDateTime aiPlanRequestedAt;

    @Column(name = "ai_plan_completed_at")
    private LocalDateTime aiPlanCompletedAt;

    public void applyAiPlan(String planId, String overview) {
        this.planId = planId;
        this.overview = overview;
    }

    public void markAiPlanPending() {
        this.aiPlanStatus = AiGenerationStatus.PENDING;
        this.aiPlanErrorMessage = null;
        this.aiPlanRequestedAt = LocalDateTime.now();
        this.aiPlanCompletedAt = null;
    }

    public void markAiPlanSucceeded() {
        this.aiPlanStatus = AiGenerationStatus.SUCCEEDED;
        this.aiPlanErrorMessage = null;
        this.aiPlanCompletedAt = LocalDateTime.now();
    }

    public void markAiPlanFailed(String errorMessage) {
        this.aiPlanStatus = AiGenerationStatus.FAILED;
        this.aiPlanErrorMessage = trimErrorMessage(errorMessage);
        this.aiPlanCompletedAt = LocalDateTime.now();
    }

    public void markAiPlanSkipped(String errorMessage) {
        this.aiPlanStatus = AiGenerationStatus.SKIPPED;
        this.aiPlanErrorMessage = trimErrorMessage(errorMessage);
        this.aiPlanCompletedAt = LocalDateTime.now();
    }

    private String trimErrorMessage(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return null;
        }
        return errorMessage.length() <= MAX_AI_ERROR_MESSAGE_LENGTH
                ? errorMessage
                : errorMessage.substring(0, MAX_AI_ERROR_MESSAGE_LENGTH);
    }
}
