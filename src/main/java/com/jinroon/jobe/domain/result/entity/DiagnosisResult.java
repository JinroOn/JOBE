package com.jinroon.jobe.domain.result.entity;

import com.jinroon.jobe.global.common.entity.BaseEntitySupport;
import com.jinroon.jobe.global.common.ai.AiGenerationStatus;

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
@Table(name = "diagnosis_results")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DiagnosisResult extends BaseEntitySupport {
    private static final int MAX_AI_ERROR_MESSAGE_LENGTH = 500;

    @Column(name = "diagnosis_session_id", nullable = false)
    private Long diagnosisSessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "competency_vector", nullable = false, columnDefinition = "JSON")
    private String competencyVector;

    @Column(name = "tendency_vector", nullable = false, columnDefinition = "JSON")
    private String tendencyVector;

    @Column(name = "top_majors_json", columnDefinition = "JSON")
    private String topMajorsJson;

    @Column(name = "share_token", length = 128)
    private String shareToken;

    @Column(name = "ai_comment", columnDefinition = "TEXT")
    private String aiComment;

    @Column(name = "weakness_focus", length = 500)
    private String weaknessFocus;

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_comment_status", nullable = false, length = 30)
    private AiGenerationStatus aiCommentStatus = AiGenerationStatus.NOT_REQUESTED;

    @Column(name = "ai_comment_error_message", length = 500)
    private String aiCommentErrorMessage;

    @Column(name = "ai_comment_requested_at")
    private LocalDateTime aiCommentRequestedAt;

    @Column(name = "ai_comment_completed_at")
    private LocalDateTime aiCommentCompletedAt;

    public static DiagnosisResult create(
            Long diagnosisSessionId,
            Long userId,
            String competencyVector,
            String tendencyVector,
            String topMajorsJson,
            String shareToken
    ) {
        DiagnosisResult result = new DiagnosisResult();
        result.diagnosisSessionId = diagnosisSessionId;
        result.userId = userId;
        result.competencyVector = competencyVector;
        result.tendencyVector = tendencyVector;
        result.topMajorsJson = topMajorsJson;
        result.shareToken = shareToken;
        return result;
    }

    public void updateRecommendationData(String competencyVector, String tendencyVector, String topMajorsJson) {
        this.competencyVector = competencyVector;
        this.tendencyVector = tendencyVector;
        this.topMajorsJson = topMajorsJson;
    }

    public void resetAiCommentForRecommendationChange() {
        this.aiComment = null;
        this.weaknessFocus = null;
        this.aiCommentStatus = AiGenerationStatus.NOT_REQUESTED;
        this.aiCommentErrorMessage = null;
        this.aiCommentRequestedAt = null;
        this.aiCommentCompletedAt = null;
    }

    public void applyAiComment(String aiComment, String weaknessFocus) {
        this.aiComment = aiComment;
        this.weaknessFocus = weaknessFocus;
    }

    public void markAiCommentPending() {
        this.aiCommentStatus = AiGenerationStatus.PENDING;
        this.aiCommentErrorMessage = null;
        this.aiCommentRequestedAt = LocalDateTime.now();
        this.aiCommentCompletedAt = null;
    }

    public void markAiCommentSucceeded() {
        this.aiCommentStatus = AiGenerationStatus.SUCCEEDED;
        this.aiCommentErrorMessage = null;
        this.aiCommentCompletedAt = LocalDateTime.now();
    }

    public void markAiCommentFailed(String errorMessage) {
        this.aiCommentStatus = AiGenerationStatus.FAILED;
        this.aiCommentErrorMessage = trimErrorMessage(errorMessage);
        this.aiCommentCompletedAt = LocalDateTime.now();
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
