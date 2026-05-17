package com.jinroon.jobe.plan.domain;

import com.jinroon.jobe.common.domain.BaseEntitySupport;

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
@Table(name = "major_weekly_plans")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MajorWeeklyPlan extends BaseEntitySupport {

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

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
