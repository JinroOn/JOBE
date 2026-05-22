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
@Table(name = "major_weekly_plan_items")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MajorWeeklyPlanItem extends BaseEntitySupport {

    @Column(name = "weekly_plan_id", nullable = false)
    private Long weeklyPlanId;

    @Column(name = "week_no", nullable = false)
    private Integer weekNo;

    @Column(nullable = false, length = 300)
    private String goal;

    @Column(name = "tasks_json", columnDefinition = "JSON")
    private String tasksJson;

    @Column(name = "resources_json", columnDefinition = "JSON")
    private String resourcesJson;

    @Column(length = 300)
    private String checkpoint;
}
