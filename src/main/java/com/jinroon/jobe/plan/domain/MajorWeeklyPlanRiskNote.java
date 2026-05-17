package com.jinroon.jobe.plan.domain;

import com.jinroon.jobe.common.domain.BaseEntitySupport;

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

    @Column(nullable = false, length = 500)
    private String note;
}
