package com.jinroon.jobe.domain.plan.entity;

import com.jinroon.jobe.global.common.entity.BaseEntitySupport;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "major_weekly_plan_risk_notes")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MajorWeeklyPlanRiskNote extends BaseEntitySupport {

    @Column(name = "weekly_plan_id", nullable = false)
    private Long weeklyPlanId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String note;

    public static MajorWeeklyPlanRiskNote of(Long weeklyPlanId, String note) {
        MajorWeeklyPlanRiskNote riskNote = new MajorWeeklyPlanRiskNote();
        riskNote.weeklyPlanId = weeklyPlanId;
        riskNote.note = note;
        return riskNote;
    }
}
